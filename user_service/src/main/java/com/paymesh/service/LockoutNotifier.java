package com.paymesh.service;

import com.paymesh.auth.RefreshTokens;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;

/**
 * Tells the account owner their account was locked: if it wasn't them, someone is guessing their password.
 * Must be called inside the transaction that sets the lock, so the lock and its email are committed together.
 */
@Component
public class LockoutNotifier {

    /** Users are in India (INR wallet), so times in mails are IST. */
    private static final ZoneId USER_TIME_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter UNLOCK_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a 'IST'", Locale.ENGLISH).withZone(USER_TIME_ZONE);

    private static final Duration UNLOCK_LINK_VALIDITY = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Outbox outbox;
    private final String publicUrl;

    public LockoutNotifier(Outbox outbox, @Value("${app.public-url:http://localhost:4200}") String publicUrl) {
        this.outbox = outbox;
        this.publicUrl = publicUrl;
    }

    /**
     * Also creates the one-time unlock link (only its hash is stored, replacing any earlier link), so the real owner
     * isn't stuck waiting when someone else is guessing: getting the email proves it's them.
     */
    public void accountLocked(User user, int failedAttempts) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        user.setUnlockTokenHash(RefreshTokens.hash(token));
        user.setUnlockExpiresAt(user.getLockedUntil().isBefore(Instant.now().plus(UNLOCK_LINK_VALIDITY))
                ? user.getLockedUntil() : Instant.now().plus(UNLOCK_LINK_VALIDITY));
        String link = publicUrl + "/api/auth/unlock?token=" + token;
        SendMailNotification mail = new SendMailNotification(user.getUserEmailId(),
                "PayMesh: your account was temporarily locked", body(user, failedAttempts, link));
        outbox.publish(TopicConstants.SEND_NOTIFICATION_TOPIC, mail.receiverMailId(), mail);
    }

    private static String body(User user, int failedAttempts, String link) {
        return String.format("Hi %s,%n%n"
                        + "We temporarily locked your PayMesh account after %d failed login attempts in a row. "
                        + "It unlocks automatically at %s, or right away with this one-time link:%n%s%n%n"
                        + "Nobody signed in: none of those attempts used the right password. If they weren't you, someone "
                        + "may know your mobile number or email and be guessing your password, so make sure it is strong "
                        + "and not used anywhere else.%n%n"
                        + "Thanks & Regards,%nPayMesh Team",
                user.getUserFullName(), failedAttempts, UNLOCK_TIME.format(user.getLockedUntil()), link);
    }
}
