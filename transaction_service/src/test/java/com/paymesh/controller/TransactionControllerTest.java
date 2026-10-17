package com.paymesh.controller;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.entity.Transaction;
import com.paymesh.outbox.Outbox;
import com.paymesh.enums.PaymentStatus;
import com.paymesh.repository.TransactionRepository;
import com.paymesh.security.SecurityConfig;
import com.paymesh.service.TransactionService;
import com.paymesh.idempotency.IdempotentPayments;
import com.paymesh.enums.TransactionType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = TransactionController.class)
@Import({SecurityConfig.class, TransactionService.class, StepUpPolicy.class, io.micrometer.core.instrument.simple.SimpleMeterRegistry.class})
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionRepository transactionRepository;
    @MockitoBean
    private Outbox outbox;
    @MockitoBean
    private IdempotentPayments idempotentPayments; // covered by IdempotencyIntegrationTest

    @Test
    void transactionDetailsAreHiddenFromNonParticipants() throws Exception {
        when(transactionRepository.findById("t1")).thenReturn(Optional.of(transfer(1L, 2L)));

        mockMvc.perform(get("/api/transactions/t1").with(jwt().jwt(j -> j.subject("3"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/transactions/t1").with(jwt().jwt(j -> j.subject("2"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("t1"));
    }

    @Test
    void senderIsTakenFromTheTokenAndThePaymentIsQueuedWithIt() throws Exception {
        when(transactionRepository.save(any())).thenAnswer(call -> {
            Transaction txn = call.getArgument(0);
            txn.setTransactionId("new-id");
            return txn;
        });

        mockMvc.perform(post("/api/transactions").with(jwt().jwt(j -> j.subject("1").claim("name", "Asha")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TRANSFER\",\"amount\":100,\"receiverId\":2,\"senderId\":99}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getSenderId()).isEqualTo(1L);
        assertThat(saved.getValue().getReceiverId()).isEqualTo(2L);
        verify(outbox).publish(TopicConstants.INITIATE_TRANSACTION_TOPIC, "new-id",
                new InitiateTransactionDto("new-id", 1L, 2L, new BigDecimal("100"), TransactionType.TRANSFER));
    }

    @Test
    void cannotSendMoneyToYourself() throws Exception {
        mockMvc.perform(post("/api/transactions").with(jwt().jwt(j -> j.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"TRANSFER\",\"amount\":100,\"receiverId\":1}"))
                .andExpect(status().isBadRequest());

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void historyOnlyQueriesTheCallersTransactions() throws Exception {
        when(transactionRepository.findBySenderIdOrReceiverIdOrderByPaymentInitiationDateDesc(eq(5L), eq(5L), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/transactions").with(jwt().jwt(j -> j.subject("5"))))
                .andExpect(status().isOk());

        verify(transactionRepository).findBySenderIdOrReceiverIdOrderByPaymentInitiationDateDesc(eq(5L), eq(5L), any());
    }

    @Test
    void transactionsRequireAToken() throws Exception {
        mockMvc.perform(get("/api/transactions")).andExpect(status().isUnauthorized());
        verifyNoInteractions(transactionRepository);
    }

    private static Transaction transfer(Long senderId, Long receiverId) {
        Transaction txn = new Transaction();
        txn.setTransactionId("t1");
        txn.setType(Transaction.Type.TRANSFER);
        txn.setSenderId(senderId);
        txn.setReceiverId(receiverId);
        txn.setAmount(BigDecimal.TEN);
        txn.setStatus(PaymentStatus.SUCCESSFUL);
        return txn;
    }
}
