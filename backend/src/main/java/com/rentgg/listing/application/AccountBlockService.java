package com.rentgg.listing.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.listing.domain.Listing;
import com.rentgg.listing.infrastructure.ListingRepository;
import com.rentgg.shared.error.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Busy time the owner sets on their account (they play it themselves, travel...). Renters see it as busy, search
 * skips it and no order may overlap it (DB trigger on orders). Busy time cannot cover an existing booking: the
 * owner cancels that order instead.
 */
@Service
public class AccountBlockService {

    static final Duration MAX_LENGTH = Duration.ofDays(90);
    static final Duration MAX_AHEAD = Duration.ofDays(180);
    static final int MAX_UPCOMING = 50;
    static final int MAX_NOTE = 200;

    private final ListingRepository listings;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public AccountBlockService(ListingRepository listings, NamedParameterJdbcTemplate jdbc, AuditService audit,
                               Clock clock) {
        this.listings = listings;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /** {@code note} is only shown to the owner; renters see the times alone. */
    public record BlockView(UUID id, Instant startAt, Instant endAt, String note) {}

    /** Busy time of the listing's account that has not ended yet. */
    @Transactional(readOnly = true)
    public List<BlockView> list(UUID ownerId, UUID listingId) {
        return upcoming(owned(ownerId, listingId).getGameAccountId());
    }

    @Transactional
    public List<BlockView> add(UUID ownerId, UUID listingId, Instant startAt, Instant endAt, String note) {
        UUID accountId = owned(ownerId, listingId).getGameAccountId();
        Instant now = clock.instant();
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Cần giờ bắt đầu và giờ kết thúc, kết thúc sau bắt đầu.");
        }
        if (!endAt.isAfter(now)) {
            throw ApiException.unprocessable("BLOCK_IN_PAST", "Khoảng thời gian này đã qua.");
        }
        if (startAt.isBefore(now)) {
            startAt = now; // "busy from now on"
        }
        if (Duration.between(startAt, endAt).compareTo(MAX_LENGTH) > 0) {
            throw ApiException.unprocessable("BLOCK_TOO_LONG",
                    "Mỗi lần chỉ đặt bận tối đa " + MAX_LENGTH.toDays() + " ngày. Nếu nghỉ lâu hơn, hãy tạm dừng listing.");
        }
        if (startAt.isAfter(now.plus(MAX_AHEAD))) {
            throw ApiException.unprocessable("BLOCK_TOO_FAR",
                    "Chỉ đặt bận trước tối đa " + MAX_AHEAD.toDays() + " ngày.");
        }
        String n = note == null || note.isBlank() ? null : note.trim();
        if (n != null && n.length() > MAX_NOTE) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Ghi chú tối đa " + MAX_NOTE + " ký tự.");
        }

        // Same lock as the orders trigger: no booking can slip in between the check below and the insert.
        MapSqlParameterSource p = new MapSqlParameterSource("account", accountId)
                .addValue("from", Timestamp.from(startAt)).addValue("to", Timestamp.from(endAt))
                .addValue("now", Timestamp.from(now));
        jdbc.query("select account_calendar_lock(:account)", p, rs -> null);

        List<Map<String, Object>> booked = jdbc.queryForList("""
                select start_at, upper(rental_period) as end_at from orders
                 where game_account_id = :account
                   and status in ('PENDING_PAYMENT','AWAITING_OWNER_APPROVAL','CONFIRMED','IN_RENTAL')
                   and rental_period && tstzrange(cast(:from as timestamptz), cast(:to as timestamptz), '[)')
                 order by start_at limit 1""", p);
        if (!booked.isEmpty()) {
            Map<String, Object> d = new HashMap<>();
            d.put("orderStartAt", instant(booked.get(0).get("start_at")).toString());
            d.put("orderEndAt", instant(booked.get(0).get("end_at")).toString());
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "BLOCK_OVERLAPS_ORDER",
                    "Khoảng này trùng một đơn thuê đã đặt. Chọn giờ khác, hoặc hủy đơn đó trước.", d);
        }
        Integer overlapping = jdbc.queryForObject("""
                select count(*) from account_blocks where game_account_id = :account
                   and period && tstzrange(cast(:from as timestamptz), cast(:to as timestamptz), '[)')""", p, Integer.class);
        if (overlapping != null && overlapping > 0) {
            throw ApiException.conflict("BLOCK_OVERLAP", "Khoảng này trùng một khoảng bận bạn đã đặt.");
        }
        Integer upcoming = jdbc.queryForObject(
                "select count(*) from account_blocks where game_account_id = :account and end_at > :now", p, Integer.class);
        if (upcoming != null && upcoming >= MAX_UPCOMING) {
            throw ApiException.unprocessable("TOO_MANY_BLOCKS", "Tối đa " + MAX_UPCOMING + " khoảng bận sắp tới.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into account_blocks (id, game_account_id, owner_id, start_at, end_at, note, created_at)
                values (:id, :account, :owner, :from, :to, :note, :now)""",
                p.addValue("id", id).addValue("owner", ownerId).addValue("note", n));
        audit.record(ownerId, "ACCOUNT_BLOCK_ADDED", "GameAccount", accountId, null,
                Map.of("blockId", id.toString(), "startAt", startAt.toString(), "endAt", endAt.toString()), null);
        return upcoming(accountId);
    }

    @Transactional
    public List<BlockView> remove(UUID ownerId, UUID listingId, UUID blockId) {
        UUID accountId = owned(ownerId, listingId).getGameAccountId();
        int n = jdbc.update("delete from account_blocks where id = :id and game_account_id = :account",
                new MapSqlParameterSource("id", blockId).addValue("account", accountId));
        if (n == 0) {
            throw ApiException.notFound("Khoảng bận");
        }
        audit.record(ownerId, "ACCOUNT_BLOCK_REMOVED", "GameAccount", accountId, Map.of("blockId", blockId.toString()),
                null, null);
        return upcoming(accountId);
    }

    private List<BlockView> upcoming(UUID accountId) {
        return jdbc.query("""
                select id, start_at, end_at, note from account_blocks
                 where game_account_id = :account and end_at > :now order by start_at""",
                new MapSqlParameterSource("account", accountId).addValue("now", Timestamp.from(clock.instant())),
                (rs, i) -> new BlockView(rs.getObject("id", UUID.class), rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant(), rs.getString("note")));
    }

    private Listing owned(UUID ownerId, UUID listingId) {
        return listings.findById(listingId).filter(l -> l.getOwnerId().equals(ownerId))
                .orElseThrow(() -> ApiException.notFound("Listing"));
    }

    private static Instant instant(Object v) {
        return v instanceof Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) v).toInstant();
    }
}
