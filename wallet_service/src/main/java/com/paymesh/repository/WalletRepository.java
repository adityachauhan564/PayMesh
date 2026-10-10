package com.paymesh.repository;

import jakarta.persistence.LockModeType;
import com.paymesh.entity.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserId(Long userId);

    /** SELECT ... FOR UPDATE: every write path uses this so concurrent updates can't overwrite each other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Wallet> findLockedByUserId(Long userId);

    boolean existsByUserId(Long userId);
}
