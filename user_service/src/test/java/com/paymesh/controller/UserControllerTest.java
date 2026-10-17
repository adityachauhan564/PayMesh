package com.paymesh.controller;

import com.paymesh.auth.AccessTokenDenylist;
import com.paymesh.auth.AuthConfig;
import com.paymesh.auth.RefreshTokens;
import com.paymesh.auth.JwtKeys;
import com.paymesh.auth.TokenIssuer;
import com.paymesh.auth.TwoFactor;
import com.paymesh.constants.TopicConstants;
import com.paymesh.dto.UserWalletCreationRequest;
import com.paymesh.model.User;
import com.paymesh.outbox.Outbox;
import com.paymesh.repository.UserRepository;
import com.paymesh.security.SecurityConfig;
import com.paymesh.service.LockoutNotifier;
import com.paymesh.service.LoginAttemptService;
import com.paymesh.service.UserOperationsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = UserController.class)
@Import({SecurityConfig.class, UserOperationsService.class, LoginAttemptService.class, LockoutNotifier.class,
        JwtKeys.class, TokenIssuer.class, AuthConfig.class})
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JsonMapper jsonMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private JwtKeys jwtKeys;

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

    @Test
    void loginIssuesTokenWithIssuerAndUserIdSubject() throws Exception {
        when(userRepository.findByUserMobileNo("9876543210")).thenReturn(Optional.of(user(7L)));

        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"9876543210\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Jwt token = jwtDecoder.decode(jsonMapper.readTree(body).get("token").asString());
        assertThat(token.getClaimAsString("iss")).isEqualTo("paymesh");
        assertThat(token.getSubject()).isEqualTo("7");
    }

    @Test
    void registrationQueuesWalletCreationInTheOutbox() throws Exception {
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            saved.setUserId(7L);
            return saved;
        });

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Asha Verma\",\"mobileNo\":\"9876543210\",\"emailId\":\"asha@example.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated());

        verify(outbox).publish(TopicConstants.USER_CREATION_TOPIC, "7",
                new UserWalletCreationRequest(7L, "Asha Verma", "asha@example.com"));
    }

    @Test
    void tokenWithoutIssuerIsRejected() throws Exception {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("7").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        JwtEncoder ourKeyNoIssuer = new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwtKeys.signingKey())));
        String legacyToken = ourKeyNoIssuer.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void profileIsAlwaysTheTokenOwners() throws Exception {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L)));

        mockMvc.perform(get("/api/users/me").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        verify(userRepository).findById(7L);
        verify(userRepository, never()).findById(longThat(id -> id != 7L));
    }

    @Test
    void profileRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        verify(userRepository, never()).findById(anyLong());
    }

    private User user(Long id) {
        User user = new User();
        user.setUserId(id);
        user.setUserFullName("Asha Verma");
        user.setUserMobileNo("9876543210");
        user.setUserEmailId("asha@example.com");
        user.setPasswordHash(passwordEncoder.encode("secret123"));
        return user;
    }
}
