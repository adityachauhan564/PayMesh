package com.paymesh.gateway.security;

import com.paymesh.gateway.error.ProblemDetailWriter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;

/**
 * Edge authentication only. Services still validate the same token and enforce ownership themselves;
 * the gateway adds no identity headers, so nothing downstream trusts the gateway blindly.
 * Tokens are RS256, verified against user_service's JWKS; logged-out tokens are rejected by DenylistFilter.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class GatewaySecurityConfig {

    private static final String[] PUBLIC_POST_PATHS = {"/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout"};
    private static final String[] PUBLIC_GET_PATHS = {"/actuator/health", "/actuator/health/**", "/api/auth/unlock"};

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ProblemDetailAuthenticationEntryPoint entryPoint,
                                                         ReactiveStringRedisTemplate redis,
                                                         ProblemDetailWriter problemDetailWriter) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable) // bearer tokens; the refresh cookie is SameSite=Strict
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, PUBLIC_POST_PATHS).permitAll()
                        .pathMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenConverter(authEndpointsIgnoreBearer())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesAsAuthorities()))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new DenylistFilter(redis, problemDetailWriter), SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(JwtProperties jwtProperties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwtProperties.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(jwtProperties.issuer()));
        return decoder;
    }

    /** The roles claim becomes ROLE_* authorities, as in the services. */
    private static Converter<Jwt, Mono<AbstractAuthenticationToken>> rolesAsAuthorities() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /** /api/auth/** is public: an expired token sent along (e.g. on logout) must not turn the request into a 401. */
    private static ServerAuthenticationConverter authEndpointsIgnoreBearer() {
        ServerBearerTokenAuthenticationConverter standard = new ServerBearerTokenAuthenticationConverter();
        return exchange -> exchange.getRequest().getPath().value().startsWith("/api/auth/") ? Mono.empty() : standard.convert(exchange);
    }
}
