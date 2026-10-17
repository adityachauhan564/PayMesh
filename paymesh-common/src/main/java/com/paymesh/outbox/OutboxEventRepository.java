package com.paymesh.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    long countByProducer(String producer);

    /**
     * Oldest first, row-locked: a second instance of the same service waits instead of publishing the same
     * events concurrently, which also keeps them in order.
     */
    @Query(value = "SELECT * FROM outbox_event WHERE producer = :producer ORDER BY id LIMIT :limit FOR UPDATE",
            nativeQuery = true)
    List<OutboxEvent> lockOldest(@Param("producer") String producer, @Param("limit") int limit);
}
