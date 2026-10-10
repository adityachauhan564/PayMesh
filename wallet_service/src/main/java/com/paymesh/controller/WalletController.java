package com.paymesh.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import com.paymesh.entity.Wallet;
import com.paymesh.service.WalletOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    public record WalletResponse(Long walletId, BigDecimal balance, BigDecimal dailyLimit, int dailyTransactionLimit,
                                 BigDecimal spentToday, int transactionsToday) {
        static WalletResponse of(Wallet w) {
            LocalDate today = LocalDate.now();
            return new WalletResponse(w.getWalletId(), w.getBalance(), w.getDailyLimit(), w.getDailyTransactionLimit(),
                    w.spentOn(today), w.txnCountOn(today));
        }
    }

    public record LimitsRequest(
            @NotNull @DecimalMin("100") @DecimalMax("100000") BigDecimal dailyLimit,
            @NotNull @Min(1) @Max(50) Integer dailyTransactionLimit) {
    }

    @Autowired
    private WalletOperations walletOperations;

    @GetMapping("/me")
    public WalletResponse myWallet(@AuthenticationPrincipal Jwt jwt) {
        return WalletResponse.of(walletOperations.getWallet(Long.valueOf(jwt.getSubject())));
    }

    @PutMapping("/me/limits")
    public WalletResponse updateLimits(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid LimitsRequest request) {
        return WalletResponse.of(walletOperations.updateLimits(Long.valueOf(jwt.getSubject()),
                request.dailyLimit(), request.dailyTransactionLimit()));
    }
}
