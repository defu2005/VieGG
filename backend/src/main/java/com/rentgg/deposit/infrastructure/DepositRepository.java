package com.rentgg.deposit.infrastructure;

import com.rentgg.deposit.domain.Deposit;
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

public interface DepositRepository extends JpaRepository<Deposit, UUID> {

    Optional<Deposit> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Deposit d where d.orderId = :orderId")
    Optional<Deposit> findByOrderIdForUpdate(@Param("orderId") UUID orderId);

    Page<Deposit> findByRenterId(UUID renterId, Pageable pageable);

    @Query(value = """
            select id from deposits where status = 'POST_RENTAL_HOLD' and hold_until <= :now
             order by hold_until limit :limit""", nativeQuery = true)
    List<UUID> findReleasable(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "select * from deposits where id = :id for update skip locked", nativeQuery = true)
    Optional<Deposit> lockForJob(@Param("id") UUID id);
}
