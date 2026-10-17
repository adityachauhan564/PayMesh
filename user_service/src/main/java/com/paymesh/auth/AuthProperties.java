package com.paymesh.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Session settings. accessTtl: how long a stolen access token is useful. refreshTtl: how long a user stays logged in
 * without activity. cookieSecure: off only for plain-HTTP setups that aren't localhost.
 * adminUserIds: who gets ADMIN, by immutable user id. Never by email: emails are unverified and editable, so anyone
 * could register (or rename themselves to) an admin's address.
 */
@ConfigurationProperties("auth")
public record AuthProperties(@DefaultValue("PT15M") Duration accessTtl,
                             @DefaultValue("P7D") Duration refreshTtl,
                             @DefaultValue("true") boolean cookieSecure,
                             @DefaultValue("") List<Long> adminUserIds) {

    public boolean isAdmin(long userId) {
        return adminUserIds.contains(userId);
    }
}
