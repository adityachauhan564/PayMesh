package com.paymesh.service;

import lombok.extern.slf4j.Slf4j;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.CreateTransactionRequest;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.TransactionResultDto;
import com.paymesh.entity.Transaction;
import com.paymesh.outbox.Outbox;
import com.paymesh.enums.PaymentStatus;
import com.paymesh.enums.TransactionType;
import com.paymesh.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@Service
@Slf4j
public class TransactionService {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private Outbox outbox;

    /** The payment and its event are committed together, so a PENDING payment always reaches the wallet. */
    @Transactional
    public Transaction createTransaction(Long userId, String userName, CreateTransactionRequest request) {
        Transaction txn = new Transaction();
        txn.setType(request.type());
        txn.setAmount(request.amount());
        txn.setRemark(request.remark());
        txn.setStatus(PaymentStatus.PENDING);
        switch (request.type()) {
            case TRANSFER -> {
                if (request.receiverId() == null) {
                    throw badRequest("receiverId is required to send money");
                }
                if (request.receiverId().equals(userId)) {
                    throw badRequest("You cannot send money to yourself");
                }
                txn.setSenderId(userId);
                txn.setReceiverId(request.receiverId());
            }
            case ADD_MONEY -> txn.setReceiverId(userId); // ponytail: simulated bank top-up, plug a payment gateway in here
            case WITHDRAW -> txn.setSenderId(userId);
            case BILL_PAYMENT -> {
                if (request.billCategory() == null || request.billAccount() == null || request.billAccount().isBlank()) {
                    throw badRequest("billCategory and billAccount are required for bill payments");
                }
                txn.setSenderId(userId);
                txn.setBillCategory(request.billCategory());
                txn.setBillAccount(request.billAccount());
            }
        }
        if (userId.equals(txn.getSenderId())) {
            txn.setSenderName(userName);
        } else {
            txn.setReceiverName(userName);
        }

        Transaction saved = transactionRepository.save(txn);
        InitiateTransactionDto event = new InitiateTransactionDto(saved.getTransactionId(), saved.getSenderId(), saved.getReceiverId(), saved.getAmount(),
                TransactionType.valueOf(saved.getType().name()));
        outbox.publish(TopicConstants.INITIATE_TRANSACTION_TOPIC, saved.getTransactionId(), event);
        return saved;
    }

    /** Kafka may redeliver a result, or deliver a stale one late: only a PENDING payment takes a result. */
    @Transactional
    public void updateTransactionStatus(TransactionResultDto result) {
        transactionRepository.findById(result.transactionId()).ifPresentOrElse(txn -> {
            if (txn.getStatus().isFinal()) {
                log.info("Ignoring result {} for transaction {}: already {}", result.paymentStatus(), txn.getTransactionId(), txn.getStatus());
                return;
            }
            txn.setStatus(result.paymentStatus());
            txn.setFailureReason(result.reason());
            if (result.fromName() != null) {
                txn.setSenderName(result.fromName());
            }
            if (result.toName() != null) {
                txn.setReceiverName(result.toName());
            }
            transactionRepository.save(txn);
        }, () -> log.warn("Result for unknown transaction {}", result.transactionId()));
    }

    public Transaction getTransaction(String transactionId, Long userId) {
        return transactionRepository.findById(transactionId)
                .filter(t -> Objects.equals(t.getSenderId(), userId) || Objects.equals(t.getReceiverId(), userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    public List<Transaction> getHistory(Long userId, int page, int size) {
        return transactionRepository.findBySenderIdOrReceiverIdOrderByPaymentInitiationDateDesc(userId, userId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
