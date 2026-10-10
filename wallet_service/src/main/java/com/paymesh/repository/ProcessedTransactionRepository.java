package com.paymesh.repository;

import com.paymesh.entity.ProcessedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProcessedTransactionRepository extends JpaRepository<ProcessedTransaction, String> {
}
