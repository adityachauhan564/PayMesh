package com.paymesh.consumer;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.InitiateTransactionDto;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.service.WalletOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WalletConsumer {

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WalletOperations walletOperations;

    @KafkaListener(topics = TopicConstants.USER_CREATION_TOPIC, groupId = "wallet_service.userCreation")
    public void onUserCreated(String message) {
        UserWalletCreationRequest user = jsonMapper.readValue(message, UserWalletCreationRequest.class);
        walletOperations.createWalletForNewUser(user);
    }

    @KafkaListener(topics = TopicConstants.USER_UPDATION_TOPIC, groupId = "wallet_service.userUpdation")
    public void onUserUpdated(String message) {
        walletOperations.updateOwner(jsonMapper.readValue(message, UserWalletCreationRequest.class));
    }

    @KafkaListener(topics = TopicConstants.USER_DELETION_TOPIC, groupId = "wallet_service.userDeletion")
    public void onUserDeleted(String userId) {
        walletOperations.deleteWalletOfUser(Long.valueOf(userId));
    }

    @KafkaListener(topics = TopicConstants.INITIATE_TRANSACTION_TOPIC, groupId = "wallet_service.transactions")
    public void onTransactionInitiated(String message) {
        walletOperations.applyTransaction(jsonMapper.readValue(message, InitiateTransactionDto.class));
    }
}
