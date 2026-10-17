package com.paymesh.controller;

import jakarta.validation.Valid;
import com.paymesh.dto.CreateTransactionRequest;
import com.paymesh.entity.Transaction;
import com.paymesh.idempotency.IdempotentPayments;
import com.paymesh.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final int MAX_KEY_LENGTH = 255;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private IdempotentPayments idempotentPayments;

    /**
     * Returns immediately with status PENDING; poll GET /{id} for the outcome. With an Idempotency-Key, a retry of the
     * same request returns the original response (marked Idempotent-Replayed) instead of creating a second payment.
     */
    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CreateTransactionRequest request,
                                    @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        long userId = Long.parseLong(jwt.getSubject());
        String userName = jwt.getClaimAsString("name");
        if (key == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(transactionService.createTransaction(userId, userName, request));
        }
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return toResponse(idempotentPayments.create(userId, userName, key, request));
            } catch (DataIntegrityViolationException | ConcurrencyFailureException lostRace) {
                // A concurrent duplicate committed first (or MySQL picked this request as a deadlock victim)
                Optional<IdempotentPayments.Response> winner = idempotentPayments.replayAfterRace(userId, key, request);
                if (winner.isPresent()) {
                    return toResponse(winner.get());
                }
                if (attempt == 3) {
                    throw lostRace;
                }
            }
        }
    }

    private static ResponseEntity<String> toResponse(IdempotentPayments.Response response) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
        if (response.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(response.body());
    }

    @GetMapping
    public List<Transaction> history(@AuthenticationPrincipal Jwt jwt,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return transactionService.getHistory(Long.valueOf(jwt.getSubject()), page, size);
    }

    @GetMapping("/{id}")
    public Transaction details(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return transactionService.getTransaction(id, Long.valueOf(jwt.getSubject()));
    }
}
