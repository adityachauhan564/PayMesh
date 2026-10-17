package com.paymesh.service;

import com.paymesh.dto.TransactionResultDto;
import com.paymesh.entity.Transaction;
import com.paymesh.enums.PaymentStatus;
import com.paymesh.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Kafka redelivers and results can arrive late: once a payment is final, nothing may flip it. */
@ExtendWith(MockitoExtension.class)
class TransactionStatusGuardTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private TransactionService transactionService;

    private Transaction stored(PaymentStatus status) {
        Transaction txn = new Transaction();
        txn.setTransactionId("t-1");
        txn.setStatus(status);
        txn.setType(Transaction.Type.TRANSFER);
        txn.setPaymentInitiationDate(java.time.LocalDateTime.now().minusSeconds(2));
        when(transactionRepository.findById("t-1")).thenReturn(Optional.of(txn));
        return txn;
    }

    private static TransactionResultDto result(PaymentStatus status, String reason) {
        return new TransactionResultDto("t-1", status, reason, "Asha", "Ravi");
    }

    @Test
    void pendingBecomesSuccessful() {
        Transaction txn = stored(PaymentStatus.PENDING);

        transactionService.updateTransactionStatus(result(PaymentStatus.SUCCESSFUL, null));

        assertThat(txn.getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
        verify(transactionRepository).save(txn);
    }

    @Test
    void completedPaymentIsCountedOnceWithLowCardinalityTags() {
        stored(PaymentStatus.PENDING);

        transactionService.updateTransactionStatus(result(PaymentStatus.SUCCESSFUL, null));
        transactionService.updateTransactionStatus(result(PaymentStatus.SUCCESSFUL, null)); // redelivery

        var counter = meterRegistry.get("paymesh.payments.completed").counter();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey).containsExactlyInAnyOrder("status", "type");
        assertThat(counter.getId().getTag("status")).isEqualTo("SUCCESSFUL");
        assertThat(meterRegistry.get("paymesh.payments.duration").timer().count()).isEqualTo(1);
    }

    @Test
    void finalStatusIsNeverOverwritten() {
        Transaction txn = stored(PaymentStatus.SUCCESSFUL);

        transactionService.updateTransactionStatus(result(PaymentStatus.REJECTED, "late"));

        assertThat(txn.getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
        assertThat(txn.getFailureReason()).isNull();
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void duplicateResultIsIgnored() {
        stored(PaymentStatus.REJECTED);

        transactionService.updateTransactionStatus(result(PaymentStatus.REJECTED, "Insufficient balance"));

        verify(transactionRepository, never()).save(any());
    }
}
