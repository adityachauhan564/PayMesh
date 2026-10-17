package com.paymesh.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Session settings. accessTtl: how long a stolen access token is useful. refreshTtl: how long a user stays logged in
 * without activity. cookieSecure: off only for plain-HTTP setups that aren't localhost. adminEmails: who gets ADMIN.
 */
@ConfigurationProperties("auth")
public record AuthProperties(@DefaultValue("PT15M") Duration accessTtl,
                             @DefaultValue("P7D") Duration refreshTtl,
                             @DefaultValue("true") boolean cookieSecure,
                             @DefaultValue("") List<String> adminEmails) {

    public boolean isAdmin(String email) {
        return email != null && adminEmails.stream().map(String::trim).anyMatch(email::equalsIgnoreCase);
    }
}
