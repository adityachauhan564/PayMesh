package com.paymesh;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.SendMailNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class KafkaConsumer {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username:}")
    private String mailFrom;

    @KafkaListener(topics = TopicConstants.SEND_NOTIFICATION_TOPIC, groupId = "send_notification")
    public void sendNotification(String message) throws JsonProcessingException {
        SendMailNotification mail = objectMapper.readValue(message, SendMailNotification.class);
        if (mailFrom.isBlank()) {
            log.info("MAIL_USERNAME not set, printing mail instead.\nTo: {}\nSubject: {}\n{}", mail.receiverMailId(), mail.subject(), mail.message());
            return;
        }
        SimpleMailMessage simpleMailMessage = new SimpleMailMessage();
        simpleMailMessage.setFrom(mailFrom);
        simpleMailMessage.setTo(mail.receiverMailId());
        simpleMailMessage.setSubject(mail.subject());
        simpleMailMessage.setText(mail.message());
        try {
            javaMailSender.send(simpleMailMessage);
        } catch (MailException e) {
            // A notification must never block the topic; log and move on.
            log.error("Could not send mail to {}", mail.receiverMailId(), e);
        }
    }
}
