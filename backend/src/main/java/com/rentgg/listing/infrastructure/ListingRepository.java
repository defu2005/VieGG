package com.rentgg.listing.infrastructure;

import com.rentgg.listing.domain.Listing;
import com.rentgg.listing.domain.ListingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingRepository extends JpaRepository<Listing, UUID> {

    List<Listing> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    List<Listing> findByStatusOrderByUpdatedAt(ListingStatus status);

    Page<Listing> findByStatus(ListingStatus status, Pageable pageable);

    Page<Listing> findByOwnerId(UUID ownerId, Pageable pageable);

    Page<Listing> findByOwnerIdAndGameAccountIdIn(UUID ownerId, java.util.Collection<UUID> gameAccountIds,
                                                  Pageable pageable);

    boolean existsByGameAccountId(UUID gameAccountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Listing l where l.id = :id")
    Optional<Listing> findForUpdate(@Param("id") UUID id);
}
