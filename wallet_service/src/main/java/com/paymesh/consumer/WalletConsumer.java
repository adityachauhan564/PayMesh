package com.paymesh.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.service.WalletOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class WalletConsumer {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WalletOperations walletOperations;

    @KafkaListener(topics = TopicConstants.USER_CREATION_TOPIC, groupId = "wallet_service.userCreation")
    public void onUserCreated(String message) throws JsonProcessingException {
        UserWalletCreationRequest user = objectMapper.readValue(message, UserWalletCreationRequest.class);
        walletOperations.createWalletForNewUser(user);
    }

    @KafkaListener(topics = TopicConstants.USER_UPDATION_TOPIC, groupId = "wallet_service.userUpdation")
    public void onUserUpdated(String message) throws JsonProcessingException {
        walletOperations.updateOwner(objectMapper.readValue(message, UserWalletCreationRequest.class));
    }

    @KafkaListener(topics = TopicConstants.USER_DELETION_TOPIC, groupId = "wallet_service.userDeletion")
    public void onUserDeleted(String userId) {
        walletOperations.deleteWalletOfUser(Long.valueOf(userId));
    }

    @KafkaListener(topics = TopicConstants.INITIATE_TRANSACTION_TOPIC, groupId = "wallet_service.transactions")
    public void onTransactionInitiated(String message) throws JsonProcessingException {
        walletOperations.applyTransaction(objectMapper.readValue(message, InitiateTransactionDto.class));
    }
}
