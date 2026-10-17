package com.paymesh;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.kafka.EventHeaders;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

/**
 * Sends each notification event once. Kafka redelivers, so the eventId is claimed in Redis (SET NX, 7 days)
 * before sending: claimed already = duplicate, skipped. A failed send releases the claim and rethrows, so the
 * error handler retries it and finally parks it on SEND_NOTIFICATION.DLT. If Redis is down, mail still goes out
 * (a possible duplicate beats a lost email).
 */
@Component
@Slf4j
public class KafkaConsumer {

    private static final Duration DEDUP_WINDOW = Duration.ofDays(7);

    private final JsonMapper jsonMapper;
    private final JavaMailSender javaMailSender;
    private final StringRedisTemplate redis;
    private final String mailFrom;

    public KafkaConsumer(JsonMapper jsonMapper, JavaMailSender javaMailSender, StringRedisTemplate redis,
                         @Value("${spring.mail.username:}") String mailFrom) {
        this.jsonMapper = jsonMapper;
        this.javaMailSender = javaMailSender;
        this.redis = redis;
        this.mailFrom = mailFrom;
    }

    private enum Claim { CLAIMED, DUPLICATE, UNCHECKED }

    @KafkaListener(topics = TopicConstants.SEND_NOTIFICATION_TOPIC, groupId = "send_notification")
    public void sendNotification(String message, @Header(name = EventHeaders.EVENT_ID, required = false) String eventId) {
        SendMailNotification mail = jsonMapper.readValue(message, SendMailNotification.class);
        Claim claim = claim(eventId);
        if (claim == Claim.DUPLICATE) {
            log.info("Notification event {} already sent, skipping redelivery", eventId);
            return;
        }
        try {
            send(mail);
        } catch (MailException e) {
            if (claim == Claim.CLAIMED) {
                release(eventId);
            }
            throw e;
        }
    }

    private Claim claim(String eventId) {
        if (eventId == null) {
            return Claim.UNCHECKED;
        }
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(eventId), "1", DEDUP_WINDOW)) ? Claim.CLAIMED : Claim.DUPLICATE;
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, sending notification event {} without duplicate check: {}", eventId, e.toString());
            return Claim.UNCHECKED;
        }
    }

    private void release(String eventId) {
        try {
            redis.delete(key(eventId));
        } catch (RuntimeException e) {
            log.warn("Could not release notification event {}; its retry will be skipped as a duplicate: {}", eventId, e.toString());
        }
    }

    private static String key(String eventId) {
        return "notification:sent:" + eventId;
    }

    private void send(SendMailNotification mail) {
        if (mailFrom.isBlank()) {
            log.info("MAIL_USERNAME not set, printing mail instead.\nTo: {}\nSubject: {}\n{}", mail.receiverMailId(), mail.subject(), mail.message());
            return;
        }
        SimpleMailMessage simpleMailMessage = new SimpleMailMessage();
        simpleMailMessage.setFrom(mailFrom);
        simpleMailMessage.setTo(mail.receiverMailId());
        simpleMailMessage.setSubject(mail.subject());
        simpleMailMessage.setText(mail.message());
        javaMailSender.send(simpleMailMessage);
    }
}
