package com.paymesh.service;

import lombok.extern.slf4j.Slf4j;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.dto.TransactionResultDto;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.entity.ProcessedTransaction;
import com.paymesh.entity.Wallet;
import com.paymesh.enums.PaymentStatus;
import com.paymesh.enums.TransactionType;
import com.paymesh.ledger.Ledger;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.ProcessedTransactionRepository;
import com.paymesh.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * All wallet writes. Every event a change causes is queued in the outbox inside the same transaction, so it is
 * published if and only if the change commits.
 */
@Service
@Slf4j
public class WalletOperations {

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private ProcessedTransactionRepository processedTransactionRepository;

    @Autowired
    private Outbox outbox;

    @Autowired
    private Ledger ledger;

    @Transactional
    public void createWalletForNewUser(UserWalletCreationRequest user) {
        if (walletRepository.existsByUserId(user.userId())) {
            return; // redelivered event: wallet and welcome email already exist
        }
        Wallet wallet = walletRepository.save(new Wallet(user.userId(), user.userName(), user.userEmailId()));
        ledger.openAccount(user.userId());
        log.info("Wallet {} created for userId {}", wallet.getWalletId(), user.userId());
        sendMail(new SendMailNotification(user.userEmailId(), "Welcome to PayMesh",
                String.format("Hi %s,%n%nYour PayMesh is ready. Your daily limit is Rs. %s and %d transactions per day; "
                                + "you can change these anytime.%n%nThanks & Regards,%nPayMesh Team",
                        user.userName(), wallet.getDailyLimit().toPlainString(), wallet.getDailyTransactionLimit())));
    }

    @Transactional
    public void updateOwner(UserWalletCreationRequest user) {
        walletRepository.findLockedByUserId(user.userId()).ifPresent(w -> {
            w.setOwnerName(user.userName());
            w.setOwnerEmail(user.userEmailId());
        });
    }

    @Transactional
    public void deleteWalletOfUser(Long userId) {
        walletRepository.findLockedByUserId(userId).ifPresent(w -> {
            if (w.getBalance().signum() == 0) {
                walletRepository.delete(w);
            } else {
                log.warn("User {} deleted but wallet {} still holds Rs. {}; keeping it for support", userId, w.getWalletId(), w.getBalance());
            }
        });
    }

    public Wallet getWallet(Long userId) {
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found yet, it is being created"));
    }

    @Transactional
    public Wallet updateLimits(Long userId, BigDecimal dailyLimit, int dailyTransactionLimit) {
        Wallet wallet = walletRepository.findLockedByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found"));
        wallet.setDailyLimit(dailyLimit);
        wallet.setDailyTransactionLimit(dailyTransactionLimit);
        return wallet;
    }

    @Transactional
    public void applyTransaction(InitiateTransactionDto txn) {
        if (processedTransactionRepository.existsById(txn.transactionId())) {
            // Redelivered: it was applied once, and its result was queued in that same transaction.
            log.info("Transaction {} already applied, ignoring redelivery", txn.transactionId());
            return;
        }

        // Lock in ascending userId order so two opposite transfers can't deadlock.
        Map<Long, Wallet> locked = new HashMap<>();
        Arrays.asList(txn.fromUserId(), txn.toUserId()).stream()
                .filter(Objects::nonNull).distinct().sorted()
                .forEach(id -> walletRepository.findLockedByUserId(id).ifPresent(w -> locked.put(id, w)));
        Wallet from = txn.fromUserId() == null ? null : locked.get(txn.fromUserId());
        Wallet to = txn.toUserId() == null ? null : locked.get(txn.toUserId());

        String reason = null;
        if (txn.fromUserId() != null && from == null) {
            reason = "Sender wallet not found";
        } else if (txn.toUserId() != null && to == null) {
            reason = "Receiver wallet not found";
        } else {
            try {
                if (from != null) {
                    from.debit(txn.amount(), LocalDate.now());
                }
                if (to != null) {
                    to.credit(txn.amount());
                }
            } catch (IllegalStateException e) {
                reason = e.getMessage();
            }
        }
        if (reason == null) {
            TransactionType type = txn.type() != null ? txn.type() : TransactionType.infer(txn.fromUserId(), txn.toUserId());
            ledger.post(txn.transactionId(), type, txn.fromUserId(), txn.toUserId(), txn.amount());
        }

        TransactionResultDto result = new TransactionResultDto(txn.transactionId(),
                reason == null ? PaymentStatus.SUCCESSFUL : PaymentStatus.REJECTED, reason,
                from == null ? null : from.getOwnerName(), to == null ? null : to.getOwnerName());
        processedTransactionRepository.save(new ProcessedTransaction(result));
        outbox.publish(TopicConstants.COMPLETED_TRANSACTION_TOPIC, txn.transactionId(), result);
        log.info("Transaction {} -> {} {}", txn.transactionId(), result.paymentStatus(), reason == null ? "" : reason);

        String amount = "Rs. " + txn.amount().toPlainString();
        if (reason != null) {
            if (from != null) {
                sendMail(mail(from, "Transaction failed", amount + " transaction " + txn.transactionId() + " failed: " + reason + "."));
            }
        } else {
            if (from != null) {
                sendMail(mail(from, amount + " debited", amount + " debited from your wallet"
                        + (to == null ? "" : " to " + to.getOwnerName()) + ". Transaction id: " + txn.transactionId()
                        + ". Available balance: Rs. " + from.getBalance().toPlainString() + "."));
            }
            if (to != null) {
                sendMail(mail(to, amount + " credited", amount + " credited to your wallet"
                        + (from == null ? "" : " from " + from.getOwnerName()) + ". Transaction id: " + txn.transactionId()
                        + ". Available balance: Rs. " + to.getBalance().toPlainString() + "."));
            }
        }
    }

    private void sendMail(SendMailNotification mail) {
        outbox.publish(TopicConstants.SEND_NOTIFICATION_TOPIC, mail.receiverMailId(), mail);
    }

    private static SendMailNotification mail(Wallet w, String subject, String body) {
        return new SendMailNotification(w.getOwnerEmail(), "PayMesh: " + subject,
                String.format("Hi %s,%n%n%s%n%nThanks & Regards,%nPayMesh Team", w.getOwnerName(), body));
    }
}
