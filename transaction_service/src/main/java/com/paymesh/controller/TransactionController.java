package com.paymesh.controller;

import jakarta.validation.Valid;
import com.paymesh.dto.CreateTransactionRequest;
import com.paymesh.entity.Transaction;
import com.paymesh.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    @Autowired
    private TransactionService transactionService;

    /** Returns immediately with status PENDING; poll GET /{id} for the outcome. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Transaction create(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid CreateTransactionRequest request) {
        return transactionService.createTransaction(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString("name"), request);
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
