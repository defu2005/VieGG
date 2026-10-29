package com.rentgg.payment.infrastructure;

import com.rentgg.payment.domain.Refund;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByOrderIdOrderByCreatedAt(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findForUpdate(@Param("id") UUID id);

    @Query(value = """
            select id from refunds where status = 'FAILED' and attempts < :maxAttempts and updated_at <= :before
             order by updated_at limit :limit""", nativeQuery = true)
    List<UUID> findRetryable(@Param("maxAttempts") int maxAttempts, @Param("before") Instant before,
                             @Param("limit") int limit);
}
