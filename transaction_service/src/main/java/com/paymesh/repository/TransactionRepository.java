package com.paymesh.repository;

import com.paymesh.entity.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    List<Transaction> findBySenderIdOrReceiverIdOrderByPaymentInitiationDateDesc(Long senderId, Long receiverId, Pageable pageable);
}
