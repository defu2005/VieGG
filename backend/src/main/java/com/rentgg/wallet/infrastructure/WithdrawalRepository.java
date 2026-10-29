package com.rentgg.wallet.infrastructure;

import com.rentgg.wallet.domain.Withdrawal;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface WithdrawalRepository extends JpaRepository<Withdrawal, UUID> {

    Optional<Withdrawal> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);

    Page<Withdrawal> findByStatus(String status, Pageable pageable);

    Page<Withdrawal> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<Withdrawal> findByStatusAndOwnerId(String status, UUID ownerId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Withdrawal w where w.id = :id")
    Optional<Withdrawal> findForUpdate(@Param("id") UUID id);
}
