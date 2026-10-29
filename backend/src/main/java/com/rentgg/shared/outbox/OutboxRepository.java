package com.rentgg.shared.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = """
            select id from outbox_events
             where published_at is null and attempts < :maxAttempts
               and (next_attempt_at is null or next_attempt_at <= :now)
             order by created_at, id
             limit :limit""", nativeQuery = true)
    List<UUID> findPublishable(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts,
                               @Param("limit") int limit);

    @Query(value = "select * from outbox_events where id = :id and published_at is null for update skip locked",
            nativeQuery = true)
    Optional<OutboxEvent> lockUnpublished(@Param("id") UUID id);

    @Query(value = "select count(*) from outbox_events where published_at is null", nativeQuery = true)
    long countPending();

    @Query(value = "select min(created_at) from outbox_events where published_at is null", nativeQuery = true)
    Instant oldestPending();

    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(UUID aggregateId);
}
