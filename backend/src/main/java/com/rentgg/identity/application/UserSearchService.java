package com.rentgg.identity.application;

import com.rentgg.shared.web.PageResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Staff user lookup. One search box accepts whatever the staff member has at hand: email, name (with or without
 * Vietnamese accents), user id, or the id of an order / listing / withdrawal the user is part of (full or the
 * first few characters, as shown on screen). Each hit says which key matched.
 */
@Service
public class UserSearchService {

    /** Looks like (part of) a UUID: worth matching against ids. 4 chars minimum to avoid noise. */
    private static final Pattern ID_PREFIX = Pattern.compile("^[0-9a-f-]{4,36}$");
    public static final int MAX_BRIEF_IDS = 100;

    private final NamedParameterJdbcTemplate jdbc;

    public UserSearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty strings mean "any". */
    public record Criteria(String q, String status, String kycStatus, String riskTier, String role) {}

    /** {@code matchedBy}: USER_ID, EMAIL, NAME, ORDER, LISTING, WITHDRAWAL (empty when no text query). */
    public record UserHit(UUID id, String email, String displayName, String status, String kycStatus,
                          String riskTier, List<String> roles, Instant createdAt, List<String> matchedBy) {}

    /** Just enough to show who an id is. */
    public record UserBrief(UUID id, String email, String displayName, String status) {}

    @Transactional(readOnly = true)
    public PageResponse<UserHit> search(Criteria c, int page, int size) {
        String q = c.q() == null ? "" : c.q().trim().toLowerCase(Locale.ROOT);
        String like = escapeLike(q);
        String idq = ID_PREFIX.matcher(q).matches() ? like : "";
        int safeSize = Math.max(1, Math.min(size, 100));
        int safePage = Math.max(0, page);
        var p = new MapSqlParameterSource()
                .addValue("q", q).addValue("like", like).addValue("idq", idq)
                .addValue("status", blank(c.status())).addValue("kyc", blank(c.kycStatus()))
                .addValue("risk", blank(c.riskTier())).addValue("role", blank(c.role()))
                .addValue("limit", safeSize).addValue("offset", (long) safePage * safeSize);
        List<Long> total = new ArrayList<>(List.of(0L));
        List<UserHit> rows = jdbc.query("""
                with hits as (
                  select u.*,
                         array_remove(array[
                           case when :idq <> '' and u.id::text like :idq || '%' then 'USER_ID' end,
                           case when :q <> '' and u.email like '%' || :like || '%' then 'EMAIL' end,
                           case when :q <> '' and rentgg_fold(u.display_name) like '%' || rentgg_fold(:like) || '%'
                                then 'NAME' end,
                           case when :idq <> '' and exists (select 1 from orders o
                                 where (o.renter_id = u.id or o.owner_id = u.id) and o.id::text like :idq || '%')
                                then 'ORDER' end,
                           case when :idq <> '' and exists (select 1 from listings l
                                 where l.owner_id = u.id and l.id::text like :idq || '%') then 'LISTING' end,
                           case when :idq <> '' and exists (select 1 from withdrawals w
                                 where w.owner_id = u.id and w.id::text like :idq || '%') then 'WITHDRAWAL' end
                         ], null) as matched
                    from users u
                   where (:status = '' or u.status = :status)
                     and (:kyc = '' or u.kyc_status = :kyc)
                     and (:risk = '' or u.risk_tier = :risk)
                     and (:role = '' or exists (select 1 from user_roles r where r.user_id = u.id and r.role = :role))
                )
                select h.id, h.email, h.display_name, h.status, h.kyc_status, h.risk_tier, h.created_at, h.matched,
                       array(select r.role from user_roles r where r.user_id = h.id order by r.role) as roles,
                       count(*) over () as total
                  from hits h
                 where :q = '' or cardinality(h.matched) > 0
                 order by (h.email = :q or h.id::text = :q) desc, h.created_at desc, h.id
                 limit :limit offset :offset""", p, (rs, i) -> {
            total.set(0, rs.getLong("total"));
            return new UserHit((UUID) rs.getObject("id"), rs.getString("email"), rs.getString("display_name"),
                    rs.getString("status"), rs.getString("kyc_status"), rs.getString("risk_tier"),
                    strings(rs, "roles"), rs.getTimestamp("created_at").toInstant(), strings(rs, "matched"));
        });
        long count = rows.isEmpty() && safePage > 0 ? countOnly(p) : total.get(0);
        return PageResponse.of(rows, safePage, safeSize, count);
    }

    @Transactional(readOnly = true)
    public List<UserBrief> briefs(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<UUID> list = ids.stream().distinct().limit(MAX_BRIEF_IDS).toList();
        return jdbc.query("select id, email, display_name, status from users where id in (:ids)",
                new MapSqlParameterSource("ids", list),
                (rs, i) -> new UserBrief((UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    /** Page past the end: the window count is not available, so count separately. */
    private long countOnly(MapSqlParameterSource p) {
        Long n = jdbc.queryForObject("""
                select count(*) from users u
                 where (:status = '' or u.status = :status)
                   and (:kyc = '' or u.kyc_status = :kyc)
                   and (:risk = '' or u.risk_tier = :risk)
                   and (:role = '' or exists (select 1 from user_roles r where r.user_id = u.id and r.role = :role))
                   and (:q = ''
                        or (:idq <> '' and u.id::text like :idq || '%')
                        or u.email like '%' || :like || '%'
                        or rentgg_fold(u.display_name) like '%' || rentgg_fold(:like) || '%'
                        or (:idq <> '' and exists (select 1 from orders o where (o.renter_id = u.id or o.owner_id = u.id)
                                                    and o.id::text like :idq || '%'))
                        or (:idq <> '' and exists (select 1 from listings l where l.owner_id = u.id
                                                    and l.id::text like :idq || '%'))
                        or (:idq <> '' and exists (select 1 from withdrawals w where w.owner_id = u.id
                                                    and w.id::text like :idq || '%')))""", p, Long.class);
        return n == null ? 0 : n;
    }

    private static List<String> strings(ResultSet rs, String col) throws SQLException {
        Array a = rs.getArray(col);
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
