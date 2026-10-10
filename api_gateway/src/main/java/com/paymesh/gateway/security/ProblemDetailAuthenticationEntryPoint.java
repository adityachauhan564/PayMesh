package com.paymesh.gateway.security;

import com.paymesh.gateway.error.ProblemDetailWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 401 as ProblemDetail. Deliberately generic: the reason a token was rejected (expired, bad signature,
 * wrong issuer) is not revealed to the caller.
 */
@Component
public class ProblemDetailAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final ProblemDetailWriter problemDetailWriter;

    public ProblemDetailAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException exception) {
        boolean tokenWasPresented = exception instanceof OAuth2AuthenticationException;
        exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                tokenWasPresented ? "Bearer error=\"invalid_token\"" : "Bearer");
        return problemDetailWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                tokenWasPresented ? "Invalid or expired access token" : "Authentication required");
    }
}
