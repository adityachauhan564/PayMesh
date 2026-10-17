package com.paymesh.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    Optional<RecoveryCode> findFirstByUserIdAndCodeHashAndUsedAtIsNull(Long userId, String codeHash);

    @Modifying
    @Query("DELETE FROM RecoveryCode c WHERE c.userId = :userId")
    void deleteAllOfUser(@Param("userId") Long userId);
}
