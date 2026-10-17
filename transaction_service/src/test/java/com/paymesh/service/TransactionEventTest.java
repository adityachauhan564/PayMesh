package com.paymesh.service;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.CreateTransactionRequest;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.entity.Transaction;
import com.paymesh.enums.TransactionType;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The wallet needs the type to post the right ledger accounts: a bill and a withdrawal look alike otherwise. */
@ExtendWith(MockitoExtension.class)
class TransactionEventTest {

    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private Outbox outbox;
    @InjectMocks
    private TransactionService transactionService;

    @Test
    void billPaymentEventCarriesItsType() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(call -> {
            Transaction txn = call.getArgument(0);
            txn.setTransactionId("t-1");
            return txn;
        });

        transactionService.createTransaction(1L, "Asha", new CreateTransactionRequest(Transaction.Type.BILL_PAYMENT,
                new BigDecimal("300"), null, Transaction.BillCategory.ELECTRICITY, "ACC-9", null));

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(outbox).publish(eq(TopicConstants.INITIATE_TRANSACTION_TOPIC), anyString(), event.capture());
        assertThat(((InitiateTransactionDto) event.getValue()).type()).isEqualTo(TransactionType.BILL_PAYMENT);
    }
}
