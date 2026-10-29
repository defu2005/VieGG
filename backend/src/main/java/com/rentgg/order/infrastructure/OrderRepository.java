package com.rentgg.order.infrastructure;

import com.rentgg.order.domain.Order;
import com.rentgg.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByRenterIdAndIdempotencyKey(UUID renterId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findForUpdate(@Param("id") UUID id);

    @Query(value = "select * from orders where id = :id for update skip locked", nativeQuery = true)
    Optional<Order> lockForJob(@Param("id") UUID id);

    Page<Order> findByRenterId(UUID renterId, Pageable pageable);

    Page<Order> findByRenterIdAndStatus(UUID renterId, OrderStatus status, Pageable pageable);

    Page<Order> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<Order> findByOwnerIdAndStatus(UUID ownerId, OrderStatus status, Pageable pageable);

    @Query(value = """
            select id from orders where status = 'PENDING_PAYMENT' and payment_expires_at <= :now
             order by payment_expires_at limit :limit""", nativeQuery = true)
    List<UUID> findPaymentExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            select id from orders where status = 'AWAITING_OWNER_APPROVAL' and approval_expires_at <= :now
             order by approval_expires_at limit :limit""", nativeQuery = true)
    List<UUID> findApprovalExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            select id from orders where status = 'CONFIRMED' and start_at <= :now
             order by start_at limit :limit""", nativeQuery = true)
    List<UUID> findDueToStart(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            select id from orders where status = 'IN_RENTAL' and block_end_at <= :now
             order by block_end_at limit :limit""", nativeQuery = true)
    List<UUID> findDueToEnd(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = """
            select id from orders where status = 'IN_RENTAL' and ending_reminder_sent_at is null
               and scheduled_end_at <= :threshold order by scheduled_end_at limit :limit""", nativeQuery = true)
    List<UUID> findNeedingReminder(@Param("threshold") Instant threshold, @Param("limit") int limit);

    @Query(value = """
            select id from orders where seller_hold_until <= :now and seller_released_at is null
             order by seller_hold_until limit :limit""", nativeQuery = true)
    List<UUID> findSellerHoldDue(@Param("now") Instant now, @Param("limit") int limit);
}
