package com.rentgg.dispute.infrastructure;

import com.rentgg.dispute.domain.DisputeItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DisputeItemRepository extends JpaRepository<DisputeItem, UUID> {

    List<DisputeItem> findByDisputeIdOrderByPosition(UUID disputeId);

    List<DisputeItem> findByDisputeIdInOrderByPosition(Collection<UUID> disputeIds);

    @Query("select count(i) > 0 from DisputeItem i where i.orderId = :orderId and i.riskId = :riskId and i.status = 'OPEN'")
    boolean existsOpenForRisk(@Param("orderId") UUID orderId, @Param("riskId") UUID riskId);
}
