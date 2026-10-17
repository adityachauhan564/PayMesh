package com.paymesh;

import com.paymesh.dto.SendMailNotification;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real Redis: SET NX with a TTL is the whole mechanism, so it is tested against the real thing. */
class NotificationDedupTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MAIL = JSON.writeValueAsString(new SendMailNotification("asha@example.com", "Hi", "Body"));

    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;

    static {
        REDIS.start();
        redisFactory = factory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(redisFactory);
    }

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private KafkaConsumer consumer;

    private static LettuceConnectionFactory factory(String host, int port) {
        LettuceClientConfiguration client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), client);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        consumer = new KafkaConsumer(JSON, mailSender, redis, "noreply@paymesh.test");
    }

    @AfterAll
    static void tearDown() {
        redisFactory.destroy();
    }

    @Test
    void sameEventIdSendsOnce() {
        consumer.sendNotification(MAIL, "e1");
        consumer.sendNotification(MAIL, "e1");

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    @Test
    void differentEventIdsBothSend() {
        consumer.sendNotification(MAIL, "e1");
        consumer.sendNotification(MAIL, "e2");

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void missingEventIdAlwaysSends() {
        consumer.sendNotification(MAIL, null);
        consumer.sendNotification(MAIL, null);

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void failedMailReleasesTheKeyAndRethrows() {
        doThrow(new MailSendException("smtp down")).doNothing().when(mailSender).send(any(SimpleMailMessage.class));

        assertThatExceptionOfType(MailSendException.class).isThrownBy(() -> consumer.sendNotification(MAIL, "e1"));
        consumer.sendNotification(MAIL, "e1"); // the retry

        verify(mailSender, times(2)).send(any(SimpleMailMessage.class));
    }

    @Test
    void redisDownStillSends() {
        LettuceConnectionFactory down = factory("localhost", 1);
        try {
            KafkaConsumer withoutRedis = new KafkaConsumer(JSON, mailSender, new StringRedisTemplate(down), "noreply@paymesh.test");

            assertThatNoException().isThrownBy(() -> withoutRedis.sendNotification(MAIL, "e1"));

            verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
        } finally {
            down.destroy();
        }
    }
}
