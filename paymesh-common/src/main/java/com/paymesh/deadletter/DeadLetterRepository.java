package com.paymesh.deadletter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {

    long countByStatus(DeadLetter.Status status);

    boolean existsByDltTopicAndDltPartitionAndDltOffset(String dltTopic, int dltPartition, long dltOffset);

    List<DeadLetter> findByStatusOrderById(DeadLetter.Status status);
}
