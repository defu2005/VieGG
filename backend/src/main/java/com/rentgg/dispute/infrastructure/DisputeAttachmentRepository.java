package com.rentgg.dispute.infrastructure;

import com.rentgg.dispute.domain.DisputeAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DisputeAttachmentRepository extends JpaRepository<DisputeAttachment, UUID> {

    @Query("select count(a) from DisputeAttachment a where a.disputeId = :disputeId")
    int countByDispute(@Param("disputeId") UUID disputeId);
}
