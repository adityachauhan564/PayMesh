package com.paymesh.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Locked: two tabs refreshing with the same cookie at once must not both get a new token. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.tokenHash = :hash")
    Optional<RefreshToken> findLockedByHash(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllOfUser(@Param("userId") Long userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.familyId = :family AND t.revokedAt IS NULL")
    int revokeFamily(@Param("family") String familyId, @Param("now") Instant now);
}
