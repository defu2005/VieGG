package com.rentgg.legal.application;

import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Records legal notices from game publishers. The response deadline is {@link #RESPONSE_WINDOW} after receipt; the
 * window is a product choice (the spec does not fix it) and is kept in one place.
 */
@Service
public class LegalNoticeService {

    public static final Duration RESPONSE_WINDOW = Duration.ofDays(30);
    public static final String OPEN = "OPEN";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LegalNoticeService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
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
