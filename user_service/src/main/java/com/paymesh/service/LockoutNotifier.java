package com.paymesh.service;

import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private final Outbox outbox;

    public LockoutNotifier(Outbox outbox) {
        this.outbox = outbox;
    }

    public void accountLocked(User user, int failedAttempts) {
        SendMailNotification mail = new SendMailNotification(user.getUserEmailId(),
                "PayMesh: your account was temporarily locked", body(user, failedAttempts));
        outbox.publish(TopicConstants.SEND_NOTIFICATION_TOPIC, mail.receiverMailId(), mail);
    }

    private static String body(User user, int failedAttempts) {
        return String.format("Hi %s,%n%n"
                        + "We temporarily locked your PayMesh account after %d failed login attempts in a row. "
                        + "It unlocks automatically at %s.%n%n"
                        + "Nobody signed in: none of those attempts used the right password. If they weren't you, someone "
                        + "may know your mobile number or email and be guessing your password, so make sure it is strong "
                        + "and not used anywhere else.%n%n"
                        + "Thanks & Regards,%nPayMesh Team",
                user.getUserFullName(), failedAttempts, UNLOCK_TIME.format(user.getLockedUntil()));
    }
}
