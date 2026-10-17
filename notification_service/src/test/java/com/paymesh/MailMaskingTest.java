package com.paymesh;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Mail logged instead of sent must not put the recipient's address in shipped logs. */
class MailMaskingTest {

    @Test
    void masksTheLocalPartAndKeepsTheDomain() {
        assertThat(KafkaConsumer.mask("asha.verma@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void handlesOddInput() {
        assertThat(KafkaConsumer.mask(null)).isEqualTo("***");
        assertThat(KafkaConsumer.mask("no-at-sign")).isEqualTo("***");
    }
}
