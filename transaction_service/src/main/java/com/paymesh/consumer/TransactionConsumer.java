package com.paymesh.consumer;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.TransactionResultDto;
import com.paymesh.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TransactionConsumer {

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TransactionService transactionService;

    @KafkaListener(topics = TopicConstants.COMPLETED_TRANSACTION_TOPIC, groupId = "transaction_service.completedTransactions")
    public void onTransactionCompleted(String message) {
        transactionService.updateTransactionStatus(jsonMapper.readValue(message, TransactionResultDto.class));
    }
}
