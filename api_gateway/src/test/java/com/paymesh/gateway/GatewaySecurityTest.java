package com.paymesh.gateway;

import com.paymesh.gateway.support.GatewayTestSupport;
import com.paymesh.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GatewaySecurityTest extends GatewayTestSupport {

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/register", "/api/auth/login"})
    void registrationAndLoginArePublic(String path) throws InterruptedException {
        client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isOk();

        assertThat(USER_SERVICE.takeRequest()).isNotNull();
    }

    @Test
    void publicAllowlistIsMethodSpecific() {
        expectUnauthorized(client.get().uri("/api/auth/login").exchange());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/users/lookup?mobile=9876543210", "/api/wallets/me",
            "/api/transactions", "/api/transactions/some-id", "/api/unknown"})
    void protectedAndUnknownRoutesRejectMissingToken(String path) {
        expectUnauthorized(client.get().uri(path).exchange())
                .jsonPath("$.detail").isEqualTo("Authentication required");

        assertThat(nothingForwarded()).isTrue();
    }

    static Stream<String> badTokens() {
        return Stream.of("not-a-jwt", TestTokens.expired(), TestTokens.signedWithOtherSecret(),
                TestTokens.withIssuer("someone-else"), TestTokens.withoutIssuer());
    }

    @ParameterizedTest
    @MethodSource("badTokens")
    void rejectsMalformedExpiredWronglySignedOrWrongIssuerTokens(String token) {
        expectUnauthorized(client.get().uri("/api/wallets/me").headers(h -> h.setBearerAuth(token)).exchange())
                .jsonPath("$.detail").isEqualTo("Invalid or expired access token");

        assertThat(nothingForwarded()).isTrue();
    }

    @Test
    void forwardsValidTokenUnchangedSoServicesCanRevalidateIt() throws InterruptedException {
        String token = TestTokens.valid();

        client.get().uri("/api/wallets/me").headers(h -> h.setBearerAuth(token)).exchange()
                .expectStatus().isOk();

        assertThat(WALLET_SERVICE.takeRequest().header(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
    }

    @Test
    void healthIsPublicWithoutDetails() {
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP")
                .jsonPath("$.components").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/info", "/actuator/gateway/routes", "/actuator/beans"})
    void otherManagementEndpointsAreNotExposedEvenWithAToken(String path) {
        expectUnauthorized(client.get().uri(path).exchange());
        client.get().uri(path).headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isNotFound();
    }

    private static WebTestClient.BodyContentSpec expectUnauthorized(WebTestClient.ResponseSpec response) {
        return response
                .expectStatus().isUnauthorized()
                .expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.title").isEqualTo("Unauthorized");
    }
}
