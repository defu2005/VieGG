package com.rentgg.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Append-only audit log (§12.7). Written synchronously in the caller's transaction; never contains credentials.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbc, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(UUID actorId, String action, String entityType, UUID entityId,
                       Map<String, Object> before, Map<String, Object> after, String reasonCode) {
        insert(actorId, action, entityType, entityId, before, after, reasonCode);
    }

    /** For actions performed by the current HTTP caller. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, UUID entityId, Map<String, Object> after, String reasonCode) {
        insert(CurrentUser.optionalId().orElse(null), action, entityType, entityId, null, after, reasonCode);
    }

    /** Survives a rollback of the caller (denied attempts, invalid webhooks). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(UUID actorId, String action, String entityType, UUID entityId,
                                  Map<String, Object> after, String reasonCode) {
        insert(actorId, action, entityType, entityId, null, after, reasonCode);
    }

    private void insert(UUID actorId, String action, String entityType, UUID entityId,
                        Map<String, Object> before, Map<String, Object> after, String reasonCode) {
        String ip = null;
        String ua = null;
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            ip = req.getRemoteAddr();
            ua = req.getHeader("User-Agent");
            if (ua != null && ua.length() > 300) {
                ua = ua.substring(0, 300);
            }
        }
        String roles = String.join(",", new TreeSet<>(CurrentUser.roles()));
        jdbc.update("""
                insert into audit_logs (id, actor_id, actor_roles, action, entity_type, entity_id, before, after,
                                        reason_code, ip, user_agent, created_at)
                values (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)""",
                UUID.randomUUID(), actorId, roles.isEmpty() ? null : roles, action, entityType, entityId,
                json(before), json(after), reasonCode, ip, ua, Timestamp.from(clock.instant()));
    }

    private String json(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
