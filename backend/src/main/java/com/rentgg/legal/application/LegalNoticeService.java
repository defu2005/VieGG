package com.rentgg.legal.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records legal notices from game publishers. The response deadline is {@link #RESPONSE_WINDOW} after receipt; the
 * window is a product choice (the spec does not fix it) and is kept in one place.
 */
@Service
public class LegalNoticeService {

    public static final Duration RESPONSE_WINDOW = Duration.ofDays(30);
    public static final Duration REMIND_BEFORE = Duration.ofDays(3);
    public static final String OPEN = "OPEN";
    public static final String RESPONDED = "RESPONDED";
    public static final String CLOSED = "CLOSED";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditService audit;
    private final OutboxService outbox;

    public LegalNoticeService(JdbcTemplate jdbc, Clock clock, AuditService audit, OutboxService outbox) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.outbox = outbox;
    }

    @Transactional
    public Notice record(UUID recordedBy, Command c) {
        if (c.gameId() != null && jdbc.queryForObject("select count(*) from games where id = ?", Long.class, c.gameId()) == 0) {
            throw ApiException.notFound("Game");
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        Instant receivedAt = c.receivedAt() == null ? now : c.receivedAt();
        if (receivedAt.isAfter(now)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Thời điểm nhận không được ở tương lai.");
        }
        Instant deadline = receivedAt.plus(RESPONSE_WINDOW);
        jdbc.update("""
                insert into legal_notices (id, game_id, publisher_name, reference_no, subject, body, status,
                                           received_at, response_deadline, recorded_by, created_at)
                values (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?)""",
                id, c.gameId(), c.publisherName(), c.referenceNo(), c.subject(), c.body(),
                Timestamp.from(receivedAt), Timestamp.from(deadline), recordedBy, Timestamp.from(now));
        return get(id);
    }

    /** Staff move a notice along OPEN → RESPONDED → CLOSED. Every move is audited; nothing else changes the status. */
    @Transactional
    public Notice transition(UUID actorId, UUID id, String to, String note) {
        String text = note == null ? null : note.trim();
        if (text != null && text.length() > 1000) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Ghi chú tối đa 1000 ký tự.");
        }
        List<String> current = jdbc.queryForList("select status from legal_notices where id = ? for update", String.class, id);
        if (current.isEmpty()) {
            throw ApiException.notFound("Thông báo pháp lý");
        }
        String from = current.get(0);
        boolean allowed = (OPEN.equals(from) && RESPONDED.equals(to)) || (RESPONDED.equals(from) && CLOSED.equals(to));
        if (!allowed) {
            throw ApiException.invalidState("Không thể chuyển hồ sơ từ " + from + " sang " + to + ".");
        }
        Instant now = clock.instant();
        jdbc.update("update legal_notices set status = ?, status_changed_at = ? where id = ?", to, Timestamp.from(now), id);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", to);
        if (text != null && !text.isEmpty()) {
            after.put("note", text);
        }
        audit.record(actorId, "LEGAL_NOTICE_TRANSITIONED", "LegalNotice", id, Map.of("status", from), after, null);
        return get(id);
    }

    /**
     * Reminds the staff once when an open notice is within {@link #REMIND_BEFORE} of its deadline, and marks it overdue
     * once after the deadline. Each step locks the rows it changes and stamps them, so a re-run or a parallel run does
     * not repeat an event. Returns the number of events emitted by this call.
     */
    @Transactional
    public int markDeadlines(int batch) {
        Instant now = clock.instant();
        int n = 0;
        List<UUID> overdue = jdbc.queryForList("""
                update legal_notices set overdue_at = ? where id in (
                    select id from legal_notices
                     where status = 'OPEN' and overdue_at is null and response_deadline < ?
                     order by response_deadline limit ? for update skip locked)
                returning id""", UUID.class, Timestamp.from(now), Timestamp.from(now), batch);
        for (UUID id : overdue) {
            recordEvent(id, "LegalNoticeOverdue");
            n++;
        }
        List<UUID> soon = jdbc.queryForList("""
                update legal_notices set reminded_at = ? where id in (
                    select id from legal_notices
                     where status = 'OPEN' and reminded_at is null and overdue_at is null
                       and response_deadline >= ? and response_deadline <= ?
                     order by response_deadline limit ? for update skip locked)
                returning id""", UUID.class, Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now.plus(REMIND_BEFORE)), batch);
        for (UUID id : soon) {
            recordEvent(id, "LegalNoticeDeadlineSoon");
            n++;
        }
        return n;
    }

    private void recordEvent(UUID id, String eventType) {
        Instant deadline = jdbc.queryForObject("select response_deadline from legal_notices where id = ?",
                (rs, n) -> rs.getTimestamp(1).toInstant(), id);
        outbox.record("LegalNotice", id, eventType, Map.of("noticeId", id, "responseDeadline", deadline.toString()));
    }

    @Transactional(readOnly = true)
    public Notice get(UUID id) {
        List<Notice> rows = jdbc.query(SELECT + " where id = ?", (rs, n) -> map(rs), id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Thông báo pháp lý");
        }
        return rows.get(0);
    }

    /** Newest deadline first within the status filter, so the most urgent notices come first. */
    @Transactional(readOnly = true)
    public PageResponse<Notice> search(String status, int page, int size) {
        int s = Math.min(100, Math.max(1, size));
        int p = Math.max(0, page);
        List<Object> args = new ArrayList<>();
        String where = "";
        if (status != null && !status.isBlank()) {
            where = " where status = ?";
            args.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from legal_notices" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<Notice> rows = jdbc.query(SELECT + where + " order by response_deadline limit ? offset ?", (rs, n) -> map(rs),
                pageArgs.toArray());
        return PageResponse.of(rows, p, s, total == null ? 0 : total);
    }

    private static final String SELECT = """
            select id, game_id, publisher_name, reference_no, subject, body, status, received_at, response_deadline,
                   recorded_by, created_at from legal_notices""";

    private static Notice map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Notice(rs.getObject("id", UUID.class), rs.getObject("game_id", UUID.class),
                rs.getString("publisher_name"), rs.getString("reference_no"), rs.getString("subject"),
                rs.getString("body"), rs.getString("status"), rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("response_deadline").toInstant(), rs.getObject("recorded_by", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }

    public record Command(UUID gameId, String publisherName, String referenceNo, String subject, String body,
                          Instant receivedAt) {}

    public record Notice(UUID id, UUID gameId, String publisherName, String referenceNo, String subject, String body,
                         String status, Instant receivedAt, Instant responseDeadline, UUID recordedBy, Instant createdAt) {}
}
