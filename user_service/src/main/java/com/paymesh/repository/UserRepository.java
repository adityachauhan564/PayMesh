package com.paymesh.repository;

import jakarta.persistence.LockModeType;
import com.paymesh.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUserMobileNo(String mobileNo);

    Optional<User> findByUserEmailId(String emailId);

    boolean existsByUserMobileNo(String mobileNo);

    boolean existsByUserEmailId(String emailId);

    boolean existsByUserPan(String pan);

    /** SELECT ... FOR UPDATE, so concurrent failed logins can't lose a count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedByUserId(Long userId);
}
