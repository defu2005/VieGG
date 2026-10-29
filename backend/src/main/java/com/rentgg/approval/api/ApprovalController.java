package com.rentgg.approval.api;

import com.rentgg.approval.application.ApprovalService;
import com.rentgg.approval.domain.ApprovalRequest;
import com.rentgg.approval.domain.ApprovalRequest.ActionType;
import com.rentgg.approval.domain.ApprovalRequest.Status;
import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.domain.TosStatus;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.rentgg.shared.web.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class ApprovalController {

    private final ApprovalService approvals;
    private final CatalogService catalog;

    public ApprovalController(ApprovalService approvals, CatalogService catalog) {
        this.approvals = approvals;
        this.catalog = catalog;
    }

    @PostMapping("/approvals")
    @PreAuthorize("@authz.anyWithPrefix(authentication, 'approval:request:')")
    public ResponseEntity<ApprovalDto> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                              @Valid @RequestBody CreateApprovalRequest req) {
        ApprovalRequest a = approvals.create(CurrentUser.id(), req.actionType(), req.payload(),
                req.reasonCode(), key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApprovalDto.of(a));
    }

    @GetMapping("/approvals")
    @PreAuthorize("hasAuthority('approval:list')")
    public PageResponse<ApprovalDto> list(@RequestParam(required = false) Status status,
                                          @RequestParam(required = false) ActionType actionType,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return PageResponse.of(approvals.list(status, actionType, p, s), ApprovalDto::of);
    }

    @PostMapping("/approvals/{id}/approve")
    @PreAuthorize("@authz.anyWithPrefix(authentication, 'approval:decide:')")
    public ApprovalDto approve(@PathVariable UUID id) {
        return ApprovalDto.of(approvals.approve(CurrentUser.id(), id));
    }

    @PostMapping("/approvals/{id}/reject")
    @PreAuthorize("@authz.anyWithPrefix(authentication, 'approval:decide:')")
    public ApprovalDto reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest req) {
        return ApprovalDto.of(approvals.reject(CurrentUser.id(), id, req == null ? null : req.note()));
    }

    /**
     * UC-03: change a game's ToS status. Re-allowing a PROHIBITED/SUSPENDED game creates a GAME_ALLOW approval (202).
     */
    @PostMapping("/games/{id}/tos-status")
    @PreAuthorize("hasAuthority('game:change_tos')")
    public ResponseEntity<?> changeTosStatus(@PathVariable UUID id, @Valid @RequestBody TosStatusRequest req) {
        if (req.tosStatus() == TosStatus.ALLOWED && catalog.allowRequiresApproval(id)) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("gameId", id.toString());
            if (req.tosUrl() != null) {
                payload.put("tosUrl", req.tosUrl());
            }
            if (req.note() != null) {
                payload.put("reviewNote", req.note());
            }
            ApprovalRequest a = approvals.create(CurrentUser.id(), ActionType.GAME_ALLOW, payload,
                    "TOS_REVIEW", null);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApprovalDto.of(a));
        }
        return ResponseEntity.ok(catalog.changeTosStatus(CurrentUser.id(), id, req.tosStatus(), req.tosUrl(), req.note(), null));
    }

    public record CreateApprovalRequest(@NotNull ActionType actionType, @NotNull Map<String, Object> payload,
                                        @NotBlank String reasonCode) {}

    public record RejectRequest(String note) {}

    public record TosStatusRequest(@NotNull TosStatus tosStatus, String tosUrl, String note) {}

    public record ApprovalDto(UUID id, ActionType actionType, Map<String, Object> payload, UUID requestedBy,
                              String reasonCode, Status status, UUID approvedBy, Instant decidedAt, Instant executedAt,
                              Instant expiresAt, String resultNote, Instant createdAt) {
        static ApprovalDto of(ApprovalRequest a) {
            return new ApprovalDto(a.getId(), a.getActionType(), a.getPayload(), a.getRequestedBy(), a.getReasonCode(),
                    a.getStatus(), a.getApprovedBy(), a.getDecidedAt(), a.getExecutedAt(), a.getExpiresAt(),
                    a.getResultNote(), a.getCreatedAt());
        }
    }
}
