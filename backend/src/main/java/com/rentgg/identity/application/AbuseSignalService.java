package com.rentgg.identity.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Records abuse signals (MVP: store + show to Support; scoring is UC-14 Phase 1). */
@Service
public class AbuseSignalService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AbuseSignalService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public void record(UUID userId, String type, UUID orderId, Map<String, Object> details) {
        try {
            jdbc.update("insert into abuse_signals (id, user_id, type, order_id, details, created_at) values (?,?,?,?,?::jsonb,?)",
                    UUID.randomUUID(), userId, type, orderId,
                    details == null ? null : mapper.writeValueAsString(details), Timestamp.from(clock.instant()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Like {@link #record} but commits on its own, so the signal survives when the caller then throws and rolls back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(UUID userId, String type, UUID orderId, Map<String, Object> details) {
        record(userId, type, orderId, details);
    }

    @Transactional(readOnly = true)
    public List<Signal> list(UUID userId) {
        return jdbc.query("select type, order_id, created_at from abuse_signals where user_id = ? order by created_at desc",
                (rs, i) -> new Signal(rs.getString(1), (UUID) rs.getObject(2), rs.getTimestamp(3).toInstant()), userId);
    }

    @Transactional(readOnly = true)
    public boolean exists(UUID userId, String type, UUID orderId) {
        Integer n = jdbc.queryForObject("select count(*) from abuse_signals where user_id = ? and type = ? and order_id = ?",
                Integer.class, userId, type, orderId);
        return n != null && n > 0;
    }

    public record Signal(String type, UUID orderId, Instant createdAt) {}
}
