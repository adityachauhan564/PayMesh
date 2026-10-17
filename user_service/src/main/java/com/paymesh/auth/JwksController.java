package com.paymesh.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** The public half of the signing key, for the gateway and services to verify tokens with (cached on their side). */
@RestController
public class JwksController {

    private final JwtKeys keys;

    public JwksController(JwtKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return keys.publicJwks().toJSONObject();
    }
}
