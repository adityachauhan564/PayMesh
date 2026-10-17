package com.paymesh.consumer;

import com.paymesh.constants.TopicConstants;
import com.paymesh.deadletter.DeadLetterStore;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.paymesh.kafka.KafkaErrorHandling.DLT_SUFFIX;

/** Stores what WalletConsumer gave up on, for replay. */
// ponytail: one consumer service per topic today; filter on the kafka_dlt-original-consumer-group header if a topic gains a second consumer
@Component
public class DeadLetterConsumer {

    @Autowired
    private DeadLetterStore deadLetterStore;

    @KafkaListener(groupId = "wallet_service.deadLetters", topics = {
            TopicConstants.USER_CREATION_TOPIC + DLT_SUFFIX,
            TopicConstants.USER_UPDATION_TOPIC + DLT_SUFFIX,
            TopicConstants.USER_DELETION_TOPIC + DLT_SUFFIX,
            TopicConstants.INITIATE_TRANSACTION_TOPIC + DLT_SUFFIX})
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        deadLetterStore.record(record);
    }
}
