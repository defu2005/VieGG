package com.rentgg.approval.infrastructure;

import com.rentgg.approval.domain.ApprovalRequest;
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

public interface ApprovalRepository extends JpaRepository<ApprovalRequest, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalRequest a where a.id = :id")
    Optional<ApprovalRequest> findForUpdate(@Param("id") UUID id);

    Optional<ApprovalRequest> findByRequestedByAndIdempotencyKey(UUID requestedBy, String idempotencyKey);

    List<ApprovalRequest> findByStatusOrderByCreatedAt(ApprovalRequest.Status status);

    List<ApprovalRequest> findByStatusAndActionTypeOrderByCreatedAt(ApprovalRequest.Status status,
                                                                    ApprovalRequest.ActionType actionType);

    List<ApprovalRequest> findAllByOrderByCreatedAtDesc();

    Page<ApprovalRequest> findByStatus(ApprovalRequest.Status status, Pageable pageable);

    Page<ApprovalRequest> findByStatusAndActionType(ApprovalRequest.Status status,
                                                    ApprovalRequest.ActionType actionType, Pageable pageable);

    Page<ApprovalRequest> findByActionType(ApprovalRequest.ActionType actionType, Pageable pageable);
}
