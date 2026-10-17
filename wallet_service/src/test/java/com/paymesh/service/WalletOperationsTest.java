package com.paymesh.service;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.dto.TransactionResultDto;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.entity.Wallet;
import com.paymesh.ledger.Ledger;
import com.paymesh.enums.PaymentStatus;
import com.paymesh.enums.TransactionType;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.ProcessedTransactionRepository;
import com.paymesh.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every wallet change queues its events in the same transaction (the outbox), so none can be lost after commit. */
@ExtendWith(MockitoExtension.class)
class WalletOperationsTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private ProcessedTransactionRepository processedTransactionRepository;
    @Mock
    private Outbox outbox;
    @Mock
    private Ledger ledger;
    @InjectMocks
    private WalletOperations walletOperations;

    @Test
    void newUserGetsAWalletAndAWelcomeEmail() {
        when(walletRepository.save(any(Wallet.class))).thenAnswer(call -> call.getArgument(0));

        walletOperations.createWalletForNewUser(new UserWalletCreationRequest(7L, "Asha", "asha@example.com"));

        verify(walletRepository).save(any(Wallet.class));
        verify(ledger).openAccount(7L);
        SendMailNotification mail = queuedMail("asha@example.com");
        assertThat(mail.subject()).isEqualTo("Welcome to PayMesh");
    }

    @Test
    void aRepeatedUserCreatedEventNeitherRecreatesTheWalletNorEmailsAgain() {
        when(walletRepository.existsByUserId(7L)).thenReturn(true);

        walletOperations.createWalletForNewUser(new UserWalletCreationRequest(7L, "Asha", "asha@example.com"));

        verify(walletRepository, never()).save(any());
        verifyNoInteractions(outbox);
    }

    @Test
    void transferQueuesItsResultAndBothEmails() {
        Wallet asha = wallet(1L, "Asha", "500");
        Wallet bala = wallet(2L, "Bala", "0");
        when(walletRepository.findLockedByUserId(1L)).thenReturn(Optional.of(asha));
        when(walletRepository.findLockedByUserId(2L)).thenReturn(Optional.of(bala));

        walletOperations.applyTransaction(new InitiateTransactionDto("t1", 1L, 2L, new BigDecimal("200"), TransactionType.TRANSFER));

        assertThat(asha.getBalance()).isEqualByComparingTo("300");
        assertThat(bala.getBalance()).isEqualByComparingTo("200");
        verify(outbox).publish(TopicConstants.COMPLETED_TRANSACTION_TOPIC, "t1",
                new TransactionResultDto("t1", PaymentStatus.SUCCESSFUL, null, "Asha", "Bala"));
        assertThat(queuedMail("Asha@example.com").subject()).isEqualTo("PayMesh: Rs. 200 debited");
        assertThat(queuedMail("Bala@example.com").subject()).isEqualTo("PayMesh: Rs. 200 credited");
    }

    @Test
    void rejectedPaymentQueuesTheRejectionAndTellsOnlyTheSender() {
        Wallet asha = wallet(1L, "Asha", "50");
        when(walletRepository.findLockedByUserId(1L)).thenReturn(Optional.of(asha));

        walletOperations.applyTransaction(new InitiateTransactionDto("t2", 1L, null, new BigDecimal("200"), TransactionType.WITHDRAW));

        assertThat(asha.getBalance()).isEqualByComparingTo("50");
        verify(outbox).publish(TopicConstants.COMPLETED_TRANSACTION_TOPIC, "t2",
                new TransactionResultDto("t2", PaymentStatus.REJECTED, "Insufficient balance", "Asha", null));
        assertThat(queuedMail("Asha@example.com").subject()).isEqualTo("PayMesh: Transaction failed");
    }

    @Test
    void aRedeliveredPaymentIsNotAppliedOrAnnouncedAgain() {
        when(processedTransactionRepository.existsById("t1")).thenReturn(true);

        walletOperations.applyTransaction(new InitiateTransactionDto("t1", 1L, 2L, new BigDecimal("200"), TransactionType.TRANSFER));

        verify(walletRepository, never()).findLockedByUserId(anyLong());
        verifyNoInteractions(outbox);
    }

    @Test
    void successfulPaymentIsPostedToTheLedger() {
        when(walletRepository.findLockedByUserId(7L)).thenReturn(Optional.of(wallet(7L, "Asha", "0")));

        walletOperations.applyTransaction(new InitiateTransactionDto("t-1", null, 7L, new BigDecimal("500"), TransactionType.ADD_MONEY));

        verify(ledger).post("t-1", TransactionType.ADD_MONEY, null, 7L, new BigDecimal("500"));
    }

    @Test
    void rejectedPaymentPostsNothing() {
        when(walletRepository.findLockedByUserId(1L)).thenReturn(Optional.of(wallet(1L, "Asha", "50")));

        walletOperations.applyTransaction(new InitiateTransactionDto("t2", 1L, null, new BigDecimal("200"), TransactionType.WITHDRAW));

        verify(ledger, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void untypedLegacyEventIsInferred() {
        when(walletRepository.findLockedByUserId(7L)).thenReturn(Optional.of(wallet(7L, "Asha", "0")));

        walletOperations.applyTransaction(new InitiateTransactionDto("t-3", null, 7L, new BigDecimal("500"), null));

        verify(ledger).post("t-3", TransactionType.ADD_MONEY, null, 7L, new BigDecimal("500"));
    }

    private SendMailNotification queuedMail(String to) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(TopicConstants.SEND_NOTIFICATION_TOPIC), eq(to), payload.capture());
        return (SendMailNotification) payload.getValue();
    }

    private static Wallet wallet(Long userId, String name, String balance) {
        Wallet wallet = new Wallet(userId, name, name + "@example.com");
        wallet.credit(new BigDecimal(balance));
        return wallet;
    }
}
