package com.paymesh.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.TransactionResultDto;
import com.paymesh.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionConsumer {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionService transactionService;

    @KafkaListener(topics = TopicConstants.COMPLETED_TRANSACTION_TOPIC, groupId = "transaction_service.completedTransactions")
    public void onTransactionCompleted(String message) throws JsonProcessingException {
        transactionService.updateTransactionStatus(objectMapper.readValue(message, TransactionResultDto.class));
    }
}
