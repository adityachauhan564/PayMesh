package com.paymesh.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, Long> {

    Optional<LedgerAccount> findByCode(String code);

    boolean existsByUserId(Long userId);

    /** Credits minus debits: user accounts are credit-normal. */
    @Query("SELECT COALESCE(SUM(CASE WHEN p.direction = com.paymesh.ledger.LedgerEntries.Direction.CREDIT THEN p.amount ELSE -p.amount END), 0) "
            + "FROM LedgerPosting p WHERE p.account.userId = :userId")
    BigDecimal balanceOfUser(@Param("userId") Long userId);
}
