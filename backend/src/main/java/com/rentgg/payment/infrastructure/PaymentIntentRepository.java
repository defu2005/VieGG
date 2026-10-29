package com.rentgg.payment.infrastructure;

import com.rentgg.payment.domain.PaymentIntent;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    Optional<PaymentIntent> findByOrderIdAndIdempotencyKey(UUID orderId, String idempotencyKey);

    Optional<PaymentIntent> findByProviderAndProviderRef(String provider, String providerRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentIntent p where p.provider = :provider and p.providerRef = :ref")
    Optional<PaymentIntent> findByProviderRefForUpdate(@Param("provider") String provider, @Param("ref") String ref);

    List<PaymentIntent> findByOrderIdOrderByCreatedAt(UUID orderId);

    /** Wallet top-ups are the intents without an order. */
    @Query("select p from PaymentIntent p where p.orderId is null and p.payerId = :payer and p.idempotencyKey = :key")
    Optional<PaymentIntent> findTopup(@Param("payer") UUID payerId, @Param("key") String idempotencyKey);

    @Query("select p from PaymentIntent p where p.orderId is null and p.payerId = :payer order by p.createdAt desc")
    List<PaymentIntent> findTopups(@Param("payer") UUID payerId, Pageable pageable);

    @Modifying
    @Query("update PaymentIntent p set p.status = 'EXPIRED', p.updatedAt = :now where p.orderId = :orderId and p.status = 'CREATED'")
    int expireOpenIntents(@Param("orderId") UUID orderId, @Param("now") Instant now);
}
