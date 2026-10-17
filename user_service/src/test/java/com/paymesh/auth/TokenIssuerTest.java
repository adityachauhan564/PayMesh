package com.paymesh.auth;

import com.paymesh.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenIssuerTest {

    @Test
    void accessTokenCarriesRolesAmrJtiAndExpiresIn15Minutes() throws Exception {
        JwtKeys keys = new JwtKeys("");
        TokenIssuer issuer = new TokenIssuer(keys, new AuthProperties(Duration.ofMinutes(15), Duration.ofDays(7), true, List.of()), "paymesh");
        User user = new User();
        user.setUserId(42L);
        user.setUserFullName("Asha");

        Jwt jwt = NimbusJwtDecoder.withPublicKey(keys.signingKey().toRSAPublicKey()).build()
                .decode(issuer.issueAccess(user, List.of("pwd")));

        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Asha");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaimAsStringList("amr")).containsExactly("pwd");
        assertThat(UUID.fromString(jwt.getId())).isNotNull();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getHeaders().get("kid")).isEqualTo(keys.signingKey().getKeyID());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("paymesh");
    }
}
