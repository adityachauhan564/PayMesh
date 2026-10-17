package com.paymesh.auth;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtKeysTest {

    @Test
    void generatesAKeyWhenNoneConfigured() throws Exception {
        JwtKeys keys = new JwtKeys("");

        RSAKey key = keys.signingKey();
        assertThat(key.getKeyID()).isNotBlank().isEqualTo(key.computeThumbprint().toString());
        assertThat(keys.publicJwks().getKeys()).singleElement().satisfies(k -> assertThat(k.isPrivate()).isFalse());
    }

    @Test
    void loadsAConfiguredPkcs8Key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        JwtKeys keys = new JwtKeys(pem);

        assertThat(keys.signingKey().toRSAPublicKey()).isEqualTo(pair.getPublic());
    }
}
