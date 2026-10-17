package com.paymesh.idempotency;

import com.paymesh.dto.CreateTransactionRequest;
import com.paymesh.service.TransactionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Payment creation under an Idempotency-Key. The key row is written in the same transaction as the payment and its
 * outbox event, so they commit together or not at all. A concurrent duplicate blocks on the unique (user, key)
 * index until the first commits, then fails with a duplicate key; the caller then replays the stored response.
 */
@Service
public class IdempotentPayments {

    public record Response(int status, String body, boolean replayed) {
    }

    private final IdempotencyKeyRepository keys;
    private final TransactionService transactionService;
    private final JsonMapper jsonMapper;
    private final Duration retention;

    public IdempotentPayments(IdempotencyKeyRepository keys, TransactionService transactionService, JsonMapper jsonMapper,
                              @Value("${idempotency.retention:PT24H}") Duration retention) {
        this.keys = keys;
        this.transactionService = transactionService;
        this.jsonMapper = jsonMapper;
        this.retention = retention;
    }

    @Transactional
    public Response create(long userId, String userName, String key, CreateTransactionRequest request) {
        String hash = hash(request);
        Optional<Response> stored = stored(userId, key, hash);
        if (stored.isPresent()) {
            return stored.get();
        }
        String body = jsonMapper.writeValueAsString(transactionService.createTransaction(userId, userName, request));
        keys.saveAndFlush(new IdempotencyKey(userId, key, hash, HttpStatus.ACCEPTED.value(), body));
        return new Response(HttpStatus.ACCEPTED.value(), body, false);
    }

    /** After losing the race to a concurrent duplicate: its committed response, if there is one yet. */
    public Optional<Response> replayAfterRace(long userId, String key, CreateTransactionRequest request) {
        return stored(userId, key, hash(request));
    }

    /** Idempotent, so every replica may run it; no lock needed. */
    @Scheduled(fixedDelayString = "PT1H")
    @Transactional
    public int deleteExpired() {
        return deleteExpiredAsOf(Instant.now());
    }

    @Transactional
    public int deleteExpiredAsOf(Instant now) {
        return keys.deleteCreatedBefore(now.minus(retention));
    }

    private Optional<Response> stored(long userId, String key, String hash) {
        return keys.findByUserIdAndIdemKey(userId, key).map(k -> {
            if (!k.getRequestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "Idempotency-Key was already used with a different request");
            }
            return new Response(k.getResponseStatus(), k.getResponseBody(), true);
        });
    }

    /** Re-serialised from the parsed request, so field order and whitespace in the client's JSON don't matter. */
    private String hash(CreateTransactionRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(jsonMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
