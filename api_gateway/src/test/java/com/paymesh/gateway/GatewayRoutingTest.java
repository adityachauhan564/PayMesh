package com.paymesh.gateway;

import com.paymesh.gateway.support.GatewayTestSupport;
import com.paymesh.gateway.support.StubUpstream.RecordedRequest;
import com.paymesh.gateway.support.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutingTest extends GatewayTestSupport {

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "POST, /api/auth/register,                 user",
            "POST, /api/auth/login,                    user",
            "GET,  /api/users/me,                      user",
            "PUT,  /api/users/me,                      user",
            "GET,  /api/users/lookup?mobile=9876543210, user",
            "GET,  /api/wallets/me,                    wallet",
            "PUT,  /api/wallets/me/limits,             wallet",
            "POST, /api/transactions,                  transaction",
            "GET,  /api/transactions?page=1&size=5,    transaction",
            "GET,  /api/transactions/3f2a-txn-id,      transaction"
    })
    void forwardsToOwningServiceWithPathAndQueryUnchanged(String method, String path, String owner) throws InterruptedException {
        client.method(HttpMethod.valueOf(method)).uri(path).headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.upstream").isEqualTo(owner);

        RecordedRequest forwarded = UPSTREAMS.get(owner).takeRequest();
        assertThat(forwarded).isNotNull();
        assertThat(forwarded.method()).isEqualTo(method);
        assertThat(forwarded.pathAndQuery()).isEqualTo(path);
        UPSTREAMS.forEach((name, stub) -> {
            if (!name.equals(owner)) {
                assertThat(stub.receivedNothing()).as("%s must not receive %s", name, path).isTrue();
            }
        });
    }

    @Test
    void forwardsRequestBodyAndContentType() throws InterruptedException {
        String body = "{\"username\":\"9876543210\",\"password\":\"secret123\"}";

        client.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isOk();

        RecordedRequest forwarded = USER_SERVICE.takeRequest();
        assertThat(forwarded.body()).isEqualTo(body);
        assertThat(forwarded.header("Content-Type")).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @ParameterizedTest
    @CsvSource({"/api/notifications", "/api/unknown", "/api/walletsX/me", "/internal/anything"})
    void unknownPathsAreNotForwarded(String path) {
        client.get().uri(path).headers(h -> h.setBearerAuth(TestTokens.valid())).exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Resource not found")
                .jsonPath("$.instance").isEqualTo(path);

        assertThat(nothingForwarded()).isTrue();
    }
}
