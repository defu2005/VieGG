package com.rentgg.audit.application;

import com.rentgg.shared.web.PageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read side of the append-only audit log, for staff with audit:read. IP and user agent are not returned. */
@Service
public class AuditQueryService {

    private static final int MAX_SIZE = 100;

    private final JdbcTemplate jdbc;

    public AuditQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<Entry> search(Filter f, int page, int size) {
        int s = Math.min(MAX_SIZE, Math.max(1, size));
        int p = Math.max(0, page);
        StringBuilder where = new StringBuilder(" where 1=1");
        List<Object> args = new ArrayList<>();
        if (f.entityType() != null && !f.entityType().isBlank()) {
            where.append(" and entity_type = ?");
            args.add(f.entityType());
        }
        if (f.entityId() != null) {
            where.append(" and entity_id = ?");
            args.add(f.entityId());
        }
        if (f.actorId() != null) {
            where.append(" and actor_id = ?");
            args.add(f.actorId());
        }
        if (f.action() != null && !f.action().isBlank()) {
            where.append(" and action = ?");
            args.add(f.action());
        }
        Long total = jdbc.queryForObject("select count(*) from audit_logs" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<Entry> rows = jdbc.query("""
                select id, actor_id, actor_roles, action, entity_type, entity_id, before, after, reason_code, created_at
                  from audit_logs""" + where + " order by created_at desc, id limit ? offset ?",
                (rs, n) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_roles"), rs.getString("action"), rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class), rs.getString("before"), rs.getString("after"),
                        rs.getString("reason_code"), rs.getTimestamp("created_at").toInstant()),
                pageArgs.toArray());
        return PageResponse.of(rows, p, s, total == null ? 0 : total);
    }

    public record Filter(String entityType, UUID entityId, UUID actorId, String action) {}

    public record Entry(UUID id, UUID actorId, String actorRoles, String action, String entityType, UUID entityId,
                        String before, String after, String reasonCode, Instant createdAt) {}
}
