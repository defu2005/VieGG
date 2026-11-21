package com.rentgg.dispute.application;

import com.rentgg.dispute.domain.Dispute;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.dispute.infrastructure.DisputeRepository;
import com.rentgg.shared.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    public DisputeAppealService(DisputeRepository disputes, JdbcTemplate jdbc, Clock clock) {
        this.disputes = disputes;
        this.jdbc = jdbc;
        this.clock = clock;
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
