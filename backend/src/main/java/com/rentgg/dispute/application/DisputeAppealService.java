package com.rentgg.dispute.application;

import com.rentgg.dispute.domain.Dispute;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.audit.application.AuditService;
import com.rentgg.dispute.infrastructure.DisputeRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Appeal against a decided dispute. Only the order owner may appeal, once per dispute, within {@link #WINDOW} of the
 * decision. Recording an appeal does not change the decision; reviewing appeals is a later step.
 */
@Service
public class DisputeAppealService {

    public static final Duration WINDOW = Duration.ofDays(7);
    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 1000;

    private final DisputeRepository disputes;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditService audit;
    private final OutboxService outbox;

    public DisputeAppealService(DisputeRepository disputes, JdbcTemplate jdbc, Clock clock, AuditService audit,
                                OutboxService outbox) {
        this.disputes = disputes;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional
    public Appeal file(UUID callerId, UUID disputeId, String reason) {
        Dispute d = disputes.findById(disputeId).orElseThrow(() -> ApiException.notFound("Khiếu nại"));
        List<UUID> owners = jdbc.queryForList("select owner_id from orders where id = ?", UUID.class, d.getOrderId());
        if (owners.isEmpty() || !owners.get(0).equals(callerId)) {
            // Same answer for strangers and the renter, so the id does not reveal the dispute.
            throw ApiException.notFound("Khiếu nại");
        }
        if (d.getStatus() == DisputeStatus.OPEN) {
            throw ApiException.invalidState("Khiếu nại chưa có quyết định để kháng nghị.");
        }
        Instant now = clock.instant();
        if (d.getDecidedAt() == null || now.isAfter(d.getDecidedAt().plus(WINDOW))) {
            throw ApiException.unprocessable("APPEAL_WINDOW_CLOSED", "Đã quá hạn kháng nghị (7 ngày kể từ quyết định).");
        }
        String text = reason == null ? "" : reason.trim();
        if (text.length() < MIN_REASON || text.length() > MAX_REASON) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Lý do kháng nghị từ 10 đến 1000 ký tự.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into dispute_appeals (id, dispute_id, appellant_id, reason, status, created_at) values (?, ?, ?, ?, 'PENDING', ?)",
                    id, disputeId, callerId, text, java.sql.Timestamp.from(now));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("APPEAL_ALREADY_FILED", "Bạn đã kháng nghị khiếu nại này.");
        }
        return new Appeal(id, disputeId, callerId, text, "PENDING", now);
    }

    /**
     * Support's decision on a pending appeal. Accepting records that the decision should be made again and emits
     * {@code DisputeAppealAccepted}; the money of the original decision is not moved here. Dismissing needs a note.
     */
    @Transactional
    public Appeal review(UUID adminId, UUID appealId, boolean accept, String note) {
        String text = note == null ? null : note.trim();
        if (text != null && text.length() > 1000) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Ghi chú tối đa 1000 ký tự.");
        }
        if (!accept && (text == null || text.isEmpty())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần ghi chú lý do bác bỏ kháng nghị.");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select a.dispute_id, a.appellant_id, a.reason, a.status, a.created_at from dispute_appeals a "
                        + "where a.id = ? for update", appealId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Kháng nghị");
        }
        Map<String, Object> row = rows.get(0);
        UUID disputeId = (UUID) row.get("dispute_id");
        if (!"PENDING".equals(row.get("status"))) {
            throw ApiException.invalidState("Kháng nghị này đã được xét.");
        }
        String resolution = accept ? "ACCEPTED" : "DISMISSED";
        Instant now = clock.instant();
        jdbc.update("update dispute_appeals set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ? "
                + "where id = ?", resolution, adminId, java.sql.Timestamp.from(now), text == null || text.isEmpty() ? null : text, appealId);
        if (accept) {
            outbox.record("Dispute", disputeId, "DisputeAppealAccepted",
                    Map.of("disputeId", disputeId, "appealId", appealId));
        }
        audit.record(adminId, "DISPUTE_APPEAL_REVIEWED", "DisputeAppeal", appealId,
                Map.of("status", "PENDING"), Map.of("status", resolution), null);
        return new Appeal(appealId, disputeId, (UUID) row.get("appellant_id"), (String) row.get("reason"), resolution,
                ((java.sql.Timestamp) row.get("created_at")).toInstant());
    }

    @Transactional(readOnly = true)
    public List<Appeal> forDispute(UUID disputeId) {
        return jdbc.query("select id, dispute_id, appellant_id, reason, status, created_at from dispute_appeals where dispute_id = ? order by created_at",
                (rs, n) -> new Appeal(rs.getObject("id", UUID.class), rs.getObject("dispute_id", UUID.class),
                        rs.getObject("appellant_id", UUID.class), rs.getString("reason"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant()),
                disputeId);
    }

    public record Appeal(UUID id, UUID disputeId, UUID appellantId, String reason, String status, Instant createdAt) {}
}
