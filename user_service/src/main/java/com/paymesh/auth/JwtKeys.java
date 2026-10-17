package com.paymesh.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The only private key in PayMesh. Tokens are signed with it; everyone else verifies against the public half,
 * published as JWKS. Configured as PKCS#8 PEM (JWT_PRIVATE_KEY); without one, a fresh key is generated per start,
 * which is fine for development but logs everyone out on every restart.
 */
@Component
@Slf4j
public class JwtKeys {

    private final RSAKey signingKey;

    public JwtKeys(@Value("${jwt.private-key:}") String pem) {
        this.signingKey = pem == null || pem.isBlank() ? generate() : load(pem);
    }

    public RSAKey signingKey() {
        return signingKey;
    }

    public JWKSet publicJwks() {
        return new JWKSet(signingKey.toPublicJWK());
    }

    private static RSAKey generate() {
        log.warn("JWT_PRIVATE_KEY not set: generated a signing key for this run only; sessions won't survive a restart");
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyUse(KeyUse.SIGNATURE).generate();
            return withThumbprintKid(key);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not generate an RSA signing key", e);
        }
    }

    private static RSAKey load(String pem) {
        try {
            String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            RSAPublicKey publicKey = (RSAPublicKey) rsa.generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return withThumbprintKid(new RSAKey.Builder(publicKey).privateKey(privateKey).keyUse(KeyUse.SIGNATURE).build());
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException("jwt.private-key (env JWT_PRIVATE_KEY) must be an RSA private key in PKCS#8 PEM", e);
        }
    }

    /** RFC 7638 thumbprint as kid: stable for a key, different for every other key. */
    private static RSAKey withThumbprintKid(RSAKey key) {
        try {
            return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
