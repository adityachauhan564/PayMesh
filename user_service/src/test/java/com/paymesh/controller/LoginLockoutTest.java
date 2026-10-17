package com.paymesh.controller;

import com.paymesh.auth.AccessTokenDenylist;
import com.paymesh.auth.AuthConfig;
import com.paymesh.auth.RefreshTokens;
import com.paymesh.auth.JwtKeys;
import com.paymesh.auth.TokenIssuer;
import com.paymesh.auth.TwoFactor;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.SendMailNotification;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.UserRepository;
import com.paymesh.security.SecurityConfig;
import com.paymesh.service.LockoutNotifier;
import com.paymesh.service.LoginAttemptService;
import com.paymesh.service.UserOperationsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lockout after 3 failures for 15 minutes (shortened attempt count to keep the tests readable). */
@WebMvcTest(value = UserController.class, properties = {
        "login.lockout.max-failed-attempts=3",
        "login.lockout.duration=15m"})
@Import({SecurityConfig.class, UserOperationsService.class, LoginAttemptService.class, LockoutNotifier.class,
        JwtKeys.class, TokenIssuer.class, AuthConfig.class})
class LoginLockoutTest {

    private static final String MOBILE = "9876543210";
    private static final String RIGHT_PASSWORD = "secret123";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private RefreshTokens refreshTokens; // session flow: RefreshFlowIntegrationTest
    @MockitoBean
    private AccessTokenDenylist accessTokenDenylist;
    @MockitoBean
    private TwoFactor twoFactor; // two-factor flow: TwoFactorIntegrationTest
    @MockitoBean
    private Outbox outbox;
    @MockitoBean
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private Clock clock;

    private User user;

    @BeforeEach
    void oneRegisteredUser() {
        user = new User();
        user.setUserId(7L);
        user.setUserFullName("Asha Verma");
        user.setUserMobileNo(MOBILE);
        user.setUserEmailId("asha@example.com");
        user.setPasswordHash(passwordEncoder.encode(RIGHT_PASSWORD));
        // The same instance is returned for the plain and the row-locked read, like one DB row.
        when(userRepository.findByUserMobileNo(MOBILE)).thenReturn(Optional.of(user));
        when(userRepository.findLockedByUserId(7L)).thenReturn(Optional.of(user));
        setTime(T0);
    }

    @Test
    void wrongPasswordsLockTheAccountWithProblemDetailAndRetryAfter() throws Exception {
        login("wrong-1").andExpect(status().isUnauthorized());
        login("wrong-2").andExpect(status().isUnauthorized());

        login("wrong-3")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "900"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("Too many failed login attempts. Try again in 15 minutes."));
    }

    @Test
    void lockedAccountRefusesEvenTheCorrectPassword() throws Exception {
        lockTheAccount();
        setTime(T0.plus(Duration.ofMinutes(10)));

        login(RIGHT_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void attemptsDuringTheLockDoNotExtendIt() throws Exception {
        lockTheAccount();
        setTime(T0.plus(Duration.ofMinutes(14)));
        login("wrong-again").andExpect(status().isTooManyRequests());

        setTime(T0.plus(Duration.ofMinutes(15)));
        login(RIGHT_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        login("wrong-1").andExpect(status().isUnauthorized());
        login("wrong-2").andExpect(status().isUnauthorized());
        login(RIGHT_PASSWORD).andExpect(status().isOk());

        login("wrong-3").andExpect(status().isUnauthorized());
        login("wrong-4").andExpect(status().isUnauthorized());
    }

    @Test
    void ownerIsEmailedExactlyOnceWhenTheAccountLocks() throws Exception {
        lockTheAccount();
        login("during-lock-1");
        login(RIGHT_PASSWORD);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(1)).publish(eq(TopicConstants.SEND_NOTIFICATION_TOPIC), eq("asha@example.com"), payload.capture());
        SendMailNotification mail = (SendMailNotification) payload.getValue();
        assertThat(mail.receiverMailId()).isEqualTo("asha@example.com");
        assertThat(mail.subject()).isEqualTo("PayMesh: your account was temporarily locked");
        assertThat(mail.message())
                .contains("Hi Asha Verma")
                .contains("after 3 failed login attempts in a row")
                .contains("unlocks automatically at 1 Jan 2026, 3:45 PM IST") // T0 + 15 min, in India time
                .doesNotContain("wrong-");
    }

    @Test
    void failuresThatDoNotLockSendNoEmail() throws Exception {
        login("wrong-1");
        login("wrong-2");

        verifyNoInteractions(outbox);
    }

    @Test
    void unknownUsernameIsAPlainUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"9000000000\",\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid username or password"));
    }

    private void lockTheAccount() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            login("wrong-" + attempt);
        }
    }

    private ResultActions login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + MOBILE + "\",\"password\":\"" + password + "\"}"));
    }

    private void setTime(Instant now) {
        when(clock.instant()).thenReturn(now);
    }
}
