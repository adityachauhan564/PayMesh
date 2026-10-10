package com.paymesh.controller;

import com.paymesh.entity.Wallet;
import com.paymesh.security.SecurityConfig;
import com.paymesh.service.WalletOperations;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = WalletController.class, properties = "jwt.secret=wallet-service-test-secret-at-least-32-bytes")
@Import(SecurityConfig.class)
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WalletOperations walletOperations;

    @Test
    void walletIsAlwaysTheTokenOwners() throws Exception {
        when(walletOperations.getWallet(7L)).thenReturn(new Wallet(7L, "Asha", "asha@example.com"));

        mockMvc.perform(get("/api/wallets/me").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));

        verify(walletOperations).getWallet(7L);
        verifyNoMoreInteractions(walletOperations);
    }

    @Test
    void limitsUpdateIgnoresAnyUserIdInTheBody() throws Exception {
        when(walletOperations.updateLimits(anyLong(), any(), anyInt())).thenReturn(new Wallet(7L, "Asha", "asha@example.com"));

        mockMvc.perform(put("/api/wallets/me/limits").with(jwt().jwt(j -> j.subject("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyLimit\":5000,\"dailyTransactionLimit\":5,\"userId\":99}"))
                .andExpect(status().isOk());

        verify(walletOperations).updateLimits(7L, new BigDecimal("5000"), 5);
    }

    @Test
    void invalidLimitsAreRejectedWithFieldDetails() throws Exception {
        mockMvc.perform(put("/api/wallets/me/limits").with(jwt().jwt(j -> j.subject("7")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dailyLimit\":5,\"dailyTransactionLimit\":500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail", containsString("dailyLimit")))
                .andExpect(jsonPath("$.detail", containsString("dailyTransactionLimit")));

        verifyNoInteractions(walletOperations);
    }

    @Test
    void walletRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/wallets/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(walletOperations);
    }
}
