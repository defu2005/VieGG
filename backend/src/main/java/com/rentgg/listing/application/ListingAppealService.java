package com.rentgg.listing.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.shared.error.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Appeal against a listing rejection or suspension (UC-05). Only the listing owner may appeal a given decision, once,
 * within {@link #WINDOW} of the decision. Accepting an appeal sends the listing back to the review queue; it is not
 * published by the appeal itself. Dismissing it leaves the decision in force.
 */
@Service
public class ListingAppealService {

    public static final Duration WINDOW = Duration.ofDays(7);
    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 1000;
    private static final int MAX_NOTE = 1000;

    private final JdbcTemplate jdbc;
    private final ListingService listings;
    private final AuditService audit;
    private final Clock clock;

    public ListingAppealService(JdbcTemplate jdbc, ListingService listings, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.listings = listings;
        this.audit = audit;
        this.clock = clock;
    }

    private record Decision(UUID listingId, UUID ownerId, Instant decidedAt) {}

    @Transactional
    public Appeal file(UUID callerId, UUID decisionId, String reason) {
        List<Decision> found = jdbc.query(
                "select d.listing_id, l.owner_id, d.decided_at from listing_moderation_decisions d "
                        + "join listings l on l.id = d.listing_id where d.id = ?",
                (rs, n) -> new Decision(rs.getObject("listing_id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getTimestamp("decided_at").toInstant()),
                decisionId);
        if (found.isEmpty() || !found.get(0).ownerId().equals(callerId)) {
            // Same answer for strangers and for the decision's own id, so the id does not reveal the decision.
            throw ApiException.notFound("Quyết định kiểm duyệt");
        }
        Decision d = found.get(0);
        Instant now = clock.instant();
        if (now.isAfter(d.decidedAt().plus(WINDOW))) {
            throw ApiException.unprocessable("APPEAL_WINDOW_CLOSED", "Đã quá hạn kháng nghị (7 ngày kể từ quyết định).");
        }
        String text = reason == null ? "" : reason.trim();
        if (text.length() < MIN_REASON || text.length() > MAX_REASON) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Lý do kháng nghị từ 10 đến 1000 ký tự.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("insert into listing_appeals (id, decision_id, listing_id, appellant_id, reason, status, created_at) "
                    + "values (?, ?, ?, ?, ?, 'PENDING', ?)",
                    id, decisionId, d.listingId(), callerId, text, Timestamp.from(now));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("APPEAL_ALREADY_FILED", "Bạn đã kháng nghị quyết định này.");
        }
        return new Appeal(id, decisionId, d.listingId(), callerId, text, "PENDING", now);
    }

    @Transactional(readOnly = true)
    public List<Appeal> forDecision(UUID decisionId) {
        return jdbc.query("select id, decision_id, listing_id, appellant_id, reason, status, created_at "
                        + "from listing_appeals where decision_id = ? order by created_at",
                (rs, n) -> new Appeal(rs.getObject("id", UUID.class), rs.getObject("decision_id", UUID.class),
                        rs.getObject("listing_id", UUID.class), rs.getObject("appellant_id", UUID.class),
                        rs.getString("reason"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                decisionId);
    }

    /** Staff decision on a pending appeal. Accepting reopens the listing for review; dismissing requires a note. */
    @Transactional
    public Appeal review(UUID adminId, UUID appealId, boolean accept, String note) {
        String text = note == null ? null : note.trim();
        if (text != null && text.length() > MAX_NOTE) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Ghi chú tối đa 1000 ký tự.");
        }
        if (!accept && (text == null || text.isEmpty())) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần ghi chú lý do bác bỏ kháng nghị.");
        }
        List<Appeal> found = jdbc.query("select id, decision_id, listing_id, appellant_id, reason, status, created_at "
                        + "from listing_appeals where id = ? for update",
                (rs, n) -> new Appeal(rs.getObject("id", UUID.class), rs.getObject("decision_id", UUID.class),
                        rs.getObject("listing_id", UUID.class), rs.getObject("appellant_id", UUID.class),
                        rs.getString("reason"), rs.getString("status"), rs.getTimestamp("created_at").toInstant()),
                appealId);
        if (found.isEmpty()) {
            throw ApiException.notFound("Kháng nghị");
        }
        Appeal a = found.get(0);
        if (!"PENDING".equals(a.status())) {
            throw ApiException.invalidState("Kháng nghị này đã được xét.");
        }
        String resolution = accept ? "ACCEPTED" : "DISMISSED";
        Instant now = clock.instant();
        if (accept) {
            listings.reopenAfterAppeal(adminId, a.listingId(),
                    text == null || text.isEmpty() ? "Kháng nghị được chấp nhận, chờ xét lại" : text);
        }
        jdbc.update("update listing_appeals set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ? "
                + "where id = ?", resolution, adminId, Timestamp.from(now), text == null || text.isEmpty() ? null : text, appealId);
        audit.record(adminId, "LISTING_APPEAL_REVIEWED", "ListingAppeal", appealId,
                Map.of("status", "PENDING"), Map.of("status", resolution), null);
        return new Appeal(a.id(), a.decisionId(), a.listingId(), a.appellantId(), a.reason(), resolution, a.createdAt());
    }

    public record Appeal(UUID id, UUID decisionId, UUID listingId, UUID appellantId, String reason, String status,
                         Instant createdAt) {}
}
