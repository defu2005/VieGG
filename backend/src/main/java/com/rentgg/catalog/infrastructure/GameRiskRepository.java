package com.rentgg.catalog.infrastructure;

import com.rentgg.catalog.domain.GameRisk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Game-level queries skip the risks owners added to their own listings ({@code listingId} set). */
public interface GameRiskRepository extends JpaRepository<GameRisk, UUID> {

    List<GameRisk> findByGameIdAndListingIdIsNullOrderByPosition(UUID gameId);

    List<GameRisk> findByGameIdInAndListingIdIsNullAndArchivedAtIsNullOrderByPosition(Collection<UUID> gameIds);

    List<GameRisk> findByListingIdOrderByPosition(UUID listingId);

    List<GameRisk> findByListingIdInAndArchivedAtIsNullOrderByPosition(Collection<UUID> listingIds);
}
