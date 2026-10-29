package com.rentgg.order.infrastructure;

import com.rentgg.order.domain.OrderExtension;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderExtensionRepository extends JpaRepository<OrderExtension, UUID> {

    List<OrderExtension> findByOrderIdOrderByCreatedAt(UUID orderId);

    Optional<OrderExtension> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OrderExtension e where e.orderId = :orderId and e.status = 'PENDING_PAYMENT'")
    Optional<OrderExtension> findPendingForUpdate(@Param("orderId") UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OrderExtension e where e.id = :id")
    Optional<OrderExtension> findForUpdate(@Param("id") UUID id);

    /** Orders whose pending extension was not paid in time (the job locks the order, then the extension). */
    @Query(value = """
            select order_id from order_extensions where status = 'PENDING_PAYMENT' and payment_expires_at <= :now
             order by payment_expires_at limit :limit""", nativeQuery = true)
    List<UUID> findOrdersWithExpiredExtension(@Param("now") Instant now, @Param("limit") int limit);

    /** Start of the next booking of the same account after {@code after}, or null when the calendar is free. */
    @Query("""
            select min(o.startAt) from Order o where o.gameAccountId = :accountId and o.id <> :orderId
               and o.status in :statuses and o.startAt >= :after""")
    Instant findNextBookingStart(@Param("accountId") UUID accountId, @Param("orderId") UUID orderId,
                                 @Param("after") Instant after,
                                 @Param("statuses") java.util.Collection<com.rentgg.order.domain.OrderStatus> statuses);
}
