package com.paymesh.kafka;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ProducerFailureLoggingTest {

    @Test
    void failedSendsAreLoggedWithoutTheirPayload(CapturedOutput output) {
        var listener = new KafkaProducerDefaults().kafkaProducerListener();

        listener.onError(new ProducerRecord<>("SEND_NOTIFICATION", "asha@example.com",
                "{\"receiverMailId\":\"asha@example.com\",\"message\":\"Hi Asha\"}"), null, new RuntimeException("broker down"));

        assertThat(output).contains("SEND_NOTIFICATION").doesNotContain("asha@example.com").doesNotContain("Hi Asha");
    }
}
