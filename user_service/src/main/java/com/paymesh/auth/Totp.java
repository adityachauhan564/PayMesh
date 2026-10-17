package com.paymesh.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;

/**
 * RFC 6238 TOTP as authenticator apps implement it: HMAC-SHA1, 30-second steps, 6 digits. Small enough to own
 * rather than add a dependency; the RFC's test vectors pin it down (TotpTest).
 */
public final class Totp {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int STEP_SECONDS = 30;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** 160 bits, the size RFC 4226 recommends, as base32 for the authenticator app. */
    public static String newSecret() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return base32(secret);
    }

    public static long stepAt(Instant t) {
        return t.getEpochSecond() / STEP_SECONDS;
    }

    public static String code(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(fromBase32(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f; // RFC 4226 dynamic truncation
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The step whose code matches, allowing one step of clock drift either way; -1 if none does. */
    public static long matchingStep(String base32Secret, String code, long nowStep) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }
        for (long step = nowStep - 1; step <= nowStep + 1; step++) {
            // constant-time compare, so response timing doesn't reveal how many digits matched
            if (MessageDigest.isEqual(code(base32Secret, step).getBytes(), code.getBytes())) {
                return step;
            }
        }
        return -1;
    }

    public static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        }
        return out.toString();
    }

    public static byte[] fromBase32(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase().toCharArray()) {
            int value = ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Not base32: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
