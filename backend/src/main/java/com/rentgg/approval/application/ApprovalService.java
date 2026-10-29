package com.rentgg.approval.application;

import com.rentgg.approval.domain.ApprovalRequest;
import com.rentgg.approval.domain.ApprovalRequest.ActionType;
import com.rentgg.approval.domain.ApprovalRequest.Status;
import com.rentgg.approval.infrastructure.ApprovalRepository;
import com.rentgg.audit.application.AuditService;
import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.domain.TosStatus;
import com.rentgg.config.RentGgProperties;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.security.AuthorityResolver;
import com.rentgg.shared.security.Permission;
import com.rentgg.wallet.application.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dual approval (UC-42, §8.10): requester ≠ approver (service + DB CHECK), role rules per action, expiry, and
 * execution through the target module's service in the same transaction as APPROVED → EXECUTED.
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final AuthorityResolver authorities;
    private final CatalogService catalog;
    private final GameAccountService gameAccounts;
    private final WalletService wallet;
    private final LedgerService ledger;
    private final AuditService audit;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final RentGgProperties props;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, AuthorityResolver authorities, CatalogService catalog, GameAccountService gameAccounts,
                           WalletService wallet, LedgerService ledger, AuditService audit, OutboxService outbox,
                           TransactionTemplate tx, RentGgProperties props, Clock clock) {
        this.approvals = approvals;
        this.authorities = authorities;
        this.catalog = catalog;
        this.gameAccounts = gameAccounts;
        this.wallet = wallet;
        this.ledger = ledger;
        this.audit = audit;
        this.outbox = outbox;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public ApprovalRequest create(UUID requesterId, ActionType type,
                                  Map<String, Object> payload, String reasonCode, String idempotencyKey) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reasonCode là bắt buộc.");
        }
        if (!authorities.resolve(requesterId).permissions().contains(Permission.approvalRequest(type.name()))) {
            throw ApiException.forbidden("FORBIDDEN", "Bạn không có quyền tạo yêu cầu " + type);
        }
        if (idempotencyKey != null) {
            var existing = approvals.findByRequestedByAndIdempotencyKey(requesterId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        validatePayload(type, payload);
        Instant now = clock.instant();
        ApprovalRequest a = new ApprovalRequest(type, payload, requesterId, reasonCode, now.plus(props.approval().ttl()),
                idempotencyKey, now);
        approvals.save(a);
        audit.record(requesterId, "APPROVAL_REQUESTED", "ApprovalRequest", a.getId(), null,
                Map.of("actionType", type.name()), reasonCode);
        outbox.record("ApprovalRequest", a.getId(), "ApprovalRequested", Map.of("approvalId", a.getId(),
                "actionType", type, "status", Status.PENDING, "actorId", requesterId));
        return a;
    }

    /** Approves and executes. See class doc. */
    public ApprovalRequest approve(UUID approverId, UUID approvalId) {
        String gate = tx.execute(s -> {
            ApprovalRequest a = approvals.findForUpdate(approvalId).orElseThrow(() -> ApiException.notFound("Yêu cầu phê duyệt"));
            if (a.getStatus() == Status.EXECUTED) {
                return "DONE";
            }
            checkDecider(a, approverId);
            if (a.getStatus() != Status.PENDING) {
                throw ApiException.invalidState("Yêu cầu không ở trạng thái PENDING.");
            }
            if (clock.instant().isAfter(a.getExpiresAt())) {
                a.reject(null, "APPROVAL_EXPIRED", clock.instant());
                audit.record(approverId, "APPROVAL_EXPIRED", "ApprovalRequest", a.getId(), null, null, "APPROVAL_EXPIRED");
                return "EXPIRED";
            }
            return "OK";
        });
        if ("EXPIRED".equals(gate)) {
            throw ApiException.conflict("APPROVAL_EXPIRED", "Yêu cầu phê duyệt đã hết hạn.");
        }
        if ("DONE".equals(gate)) {
            return approvals.findById(approvalId).orElseThrow();
        }
        try {
            return tx.execute(s -> {
                ApprovalRequest a = approvals.findForUpdate(approvalId).orElseThrow();
                if (a.getStatus() != Status.PENDING) {
                    throw ApiException.invalidState("Yêu cầu không ở trạng thái PENDING.");
                }
                Instant now = clock.instant();
                a.approve(approverId, now);
                approvals.flush(); // DB CHECK approved_by <> requested_by is the last line of defence
                execute(a, approverId);
                a.executed(clock.instant());
                audit.record(approverId, "APPROVAL_EXECUTED", "ApprovalRequest", a.getId(),
                        Map.of("status", "PENDING"), Map.of("status", "EXECUTED", "actionType", a.getActionType().name()),
                        a.getReasonCode());
                outbox.record("ApprovalRequest", a.getId(), "ApprovalDecided", Map.of("approvalId", a.getId(),
                        "actionType", a.getActionType(), "status", Status.EXECUTED, "actorId", approverId,
                        "userId", a.getRequestedBy()));
                return a;
            });
        } catch (ApiException e) {
            if (e.status() == HttpStatus.FORBIDDEN) {
                throw e;
            }
            // Business precondition no longer true: record the failure in its own transaction (§6.7).
            tx.executeWithoutResult(s -> approvals.findForUpdate(approvalId).ifPresent(a -> {
                if (a.getStatus() == Status.PENDING) {
                    a.reject(approverId, "EXECUTION_PRECONDITION_FAILED: " + e.getMessage(), clock.instant());
                    audit.record(approverId, "APPROVAL_EXECUTION_FAILED", "ApprovalRequest", a.getId(), null,
                            Map.of("error", e.code()), "EXECUTION_PRECONDITION_FAILED");
                }
            }));
            throw e;
        }
    }

    @Transactional
    public ApprovalRequest reject(UUID deciderId, UUID approvalId, String note) {
        ApprovalRequest a = approvals.findForUpdate(approvalId).orElseThrow(() -> ApiException.notFound("Yêu cầu phê duyệt"));
        if (a.getStatus() == Status.REJECTED) {
            return a;
        }
        checkDecider(a, deciderId);
        if (a.getStatus() != Status.PENDING) {
            throw ApiException.invalidState("Yêu cầu không ở trạng thái PENDING.");
        }
        a.reject(deciderId, note, clock.instant());
        audit.record(deciderId, "APPROVAL_REJECTED", "ApprovalRequest", a.getId(), Map.of("status", "PENDING"),
                Map.of("status", "REJECTED"), null);
        outbox.record("ApprovalRequest", a.getId(), "ApprovalDecided", Map.of("approvalId", a.getId(),
                "actionType", a.getActionType(), "status", Status.REJECTED, "actorId", deciderId, "userId", a.getRequestedBy()));
        return a;
    }

    @Transactional(readOnly = true)
    public List<ApprovalRequest> list(Status status, ActionType type) {
        if (status != null && type != null) {
            return approvals.findByStatusAndActionTypeOrderByCreatedAt(status, type);
        }
        if (status != null) {
            return approvals.findByStatusOrderByCreatedAt(status);
        }
        List<ApprovalRequest> all = approvals.findAllByOrderByCreatedAtDesc();
        return type == null ? all : all.stream().filter(a -> a.getActionType() == type).toList();
    }

    /**
     * Paged list. With a status filter the oldest request comes first (work queue);
     * without one the newest comes first (history).
     */
    @Transactional(readOnly = true)
    public Page<ApprovalRequest> list(Status status, ActionType type, int page, int size) {
        Sort.Direction dir = status != null ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, size, Sort.by(dir, "createdAt", "id"));
        if (status != null && type != null) {
            return approvals.findByStatusAndActionType(status, type, pageable);
        }
        if (status != null) {
            return approvals.findByStatus(status, pageable);
        }
        return type == null ? approvals.findAll(pageable) : approvals.findByActionType(type, pageable);
    }

    private void checkDecider(ApprovalRequest a, UUID deciderId) {
        if (a.getRequestedBy().equals(deciderId)) {
            throw ApiException.forbidden("SELF_APPROVAL_FORBIDDEN", "Không được tự duyệt yêu cầu của chính mình.");
        }
        if (!authorities.resolve(deciderId).permissions().contains(Permission.approvalDecide(a.getActionType().name()))) {
            throw ApiException.forbidden("FORBIDDEN", "Bạn không có quyền duyệt yêu cầu này.");
        }
    }

    // ================================================================= payloads & execution

    private void validatePayload(ActionType type, Map<String, Object> p) {
        if (p == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "payload là bắt buộc.");
        }
        switch (type) {
            case GAME_ALLOW -> catalog.get(uuid(p, "gameId"));
            case ACCOUNT_SECURITY_RESTORE -> gameAccounts.get(uuid(p, "gameAccountId"));
            case SELLER_HOLD_EARLY_RELEASE -> uuid(p, "orderId");
            case LEDGER_ADJUSTMENT -> postings(p);
        }
    }

    private void execute(ApprovalRequest a, UUID approvedBy) {
        Map<String, Object> p = a.getPayload();
        switch (a.getActionType()) {
            case GAME_ALLOW -> catalog.changeTosStatus(approvedBy, uuid(p, "gameId"), TosStatus.ALLOWED,
                    (String) p.get("tosUrl"), (String) p.get("reviewNote"), approvedBy);
            case ACCOUNT_SECURITY_RESTORE -> gameAccounts.restoreSecurity(uuid(p, "gameAccountId"), approvedBy);
            case SELLER_HOLD_EARLY_RELEASE -> wallet.releaseSellerHoldEarly(uuid(p, "orderId"), approvedBy);
            case LEDGER_ADJUSTMENT -> {
                List<Posting> postings = postings(p);
                for (Posting posting : postings) {
                    if (posting.account() == AccountCode.SELLER_AVAILABLE && posting.amount() > 0) {
                        ledger.lockSellerAvailable(posting.ownerUserId());
                        long available = ledger.liabilityBalance(AccountCode.SELLER_AVAILABLE, posting.ownerUserId());
                        if (posting.amount() > available) {
                            throw ApiException.unprocessable("INSUFFICIENT_AVAILABLE_BALANCE",
                                    "Điều chỉnh vượt quá số dư khả dụng của seller.");
                        }
                    }
                }
                UUID orderId = p.get("orderId") == null ? null : uuid(p, "orderId");
                ledger.post(TxType.LEDGER_ADJUSTMENT, orderId, "approval:" + a.getId(), "LEDGER_ADJUSTMENT:" + a.getId(),
                        approvedBy, postings);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Posting> postings(Map<String, Object> p) {
        Object raw = p.get("postings");
        if (!(raw instanceof List<?> list) || list.size() < 2) {
            throw ApiException.badRequest("VALIDATION_ERROR", "postings cần ít nhất 2 dòng.");
        }
        List<Posting> result = new ArrayList<>();
        long sum = 0;
        try {
            for (Object o : list) {
                Map<String, Object> m = new HashMap<>((Map<String, Object>) o);
                AccountCode code = AccountCode.valueOf(String.valueOf(m.get("accountCode")));
                UUID owner = m.get("ownerUserId") == null ? null : UUID.fromString(m.get("ownerUserId").toString());
                long amount = ((Number) m.get("amount")).longValue();
                if (amount == 0) {
                    throw ApiException.badRequest("VALIDATION_ERROR", "amount phải khác 0.");
                }
                result.add(new Posting(code, owner, amount));
                sum += amount;
            }
        } catch (IllegalArgumentException | ClassCastException | NullPointerException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "postings không hợp lệ.");
        }
        if (sum != 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tổng postings phải bằng 0.");
        }
        return result;
    }

    private static UUID uuid(Map<String, Object> p, String key) {
        try {
            return UUID.fromString(String.valueOf(p.get(key)));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", key + " không hợp lệ.");
        }
    }
}
