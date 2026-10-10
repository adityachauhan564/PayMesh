package com.paymesh.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerDefaultsTest {

    private final DefaultKafkaProducerFactoryCustomizer customizer = new KafkaProducerDefaults().shortProducerBlocking();

    @Test
    void sendsGiveUpAfterFiveSecondsInsteadOfKafkasSixtyByDefault() {
        DefaultKafkaProducerFactory<String, String> factory = factory(Map.of());

        customizer.customize(factory);

        assertThat(factory.getConfigurationProperties()).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000L);
    }

    @Test
    void aServicesOwnSettingWins() {
        DefaultKafkaProducerFactory<String, String> factory = factory(Map.of(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000"));

        customizer.customize(factory);

        assertThat(factory.getConfigurationProperties()).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "1000");
    }

    private static DefaultKafkaProducerFactory<String, String> factory(Map<String, Object> extra) {
        Map<String, Object> configs = new HashMap<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092"));
        configs.putAll(extra);
        return new DefaultKafkaProducerFactory<>(configs);
    }
}
