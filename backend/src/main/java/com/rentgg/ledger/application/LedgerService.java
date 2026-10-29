package com.rentgg.ledger.application;

import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The only way to write money (§4.4 rule 6). Append-only, balanced, idempotent per business key (§7.1).
 */
@Service
public class LedgerService {

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final Clock clock;

    public LedgerService(JdbcTemplate jdbc, EntityManager em, Clock clock) {
        this.jdbc = jdbc;
        this.em = em;
        this.clock = clock;
    }

    /**
     * Writes one balanced transaction in the caller's DB transaction.
     *
     * @return false when a transaction with the same idempotency key already exists (safe replay)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean post(TxType type, UUID orderId, String reference, String idempotencyKey, UUID createdBy,
                        List<Posting> postings) {
        List<Posting> lines = postings.stream().filter(p -> p.amount() != 0).toList();
        long sum = lines.stream().mapToLong(Posting::amount).sum();
        if (sum != 0) {
            throw new IllegalStateException("Unbalanced ledger transaction " + type + ": sum = " + sum);
        }
        if (lines.isEmpty()) {
            return false; // nothing to move (e.g. zero refund)
        }
        if (lines.size() < 2) {
            throw new IllegalStateException("Ledger transaction needs at least 2 postings");
        }
        if (exists(idempotencyKey)) {
            return false;
        }
        em.flush(); // business rows (orders) must exist before postings reference them
        UUID txId = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                insert into ledger_transactions (id, type, order_id, reference, idempotency_key, created_by, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""", txId, type.name(), orderId, reference, idempotencyKey, createdBy, now);
        for (Posting p : lines) {
            jdbc.update("""
                    insert into ledger_postings (id, transaction_id, account_id, order_id, amount, created_at)
                    values (?, ?, ?, ?, ?, ?)""",
                    UUID.randomUUID(), txId, accountId(p.account(), p.ownerUserId()), orderId, p.amount(), now);
        }
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(String idempotencyKey) {
        Integer n = jdbc.queryForObject("select count(*) from ledger_transactions where idempotency_key = ?",
                Integer.class, idempotencyKey);
        return n != null && n > 0;
    }

    /** Lazily creates SELLER_* accounts (unique (code, owner_user_id)). */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID accountId(AccountCode code, UUID owner) {
        List<UUID> found = findAccount(code, owner);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        jdbc.update("insert into ledger_accounts (id, code, owner_user_id, created_at) values (?, ?, ?, ?) on conflict do nothing",
                UUID.randomUUID(), code.name(), owner, Timestamp.from(clock.instant()));
        return findAccount(code, owner).get(0);
    }

    private List<UUID> findAccount(AccountCode code, UUID owner) {
        return owner == null
                ? jdbc.queryForList("select id from ledger_accounts where code = ? and owner_user_id is null", UUID.class, code.name())
                : jdbc.queryForList("select id from ledger_accounts where code = ? and owner_user_id = ?", UUID.class, code.name(), owner);
    }

    /**
     * Serializes every operation that decreases a seller's available balance (§15.3).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSellerAvailable(UUID seller) {
        UUID id = accountId(AccountCode.SELLER_AVAILABLE, seller);
        jdbc.queryForObject("select id from ledger_accounts where id = ? for update", UUID.class, id);
    }

    /** Raw signed balance: SUM(amount). */
    @Transactional(readOnly = true)
    public long rawBalance(AccountCode code, UUID owner) {
        Long v = owner == null
                ? jdbc.queryForObject("""
                    select coalesce(sum(p.amount), 0) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                     where a.code = ? and a.owner_user_id is null""", Long.class, code.name())
                : jdbc.queryForObject("""
                    select coalesce(sum(p.amount), 0) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                     where a.code = ? and a.owner_user_id = ?""", Long.class, code.name(), owner);
        return v == null ? 0 : v;
    }

    /** Liability/revenue balance shown as a positive number (−SUM), §7.7. */
    @Transactional(readOnly = true)
    public long liabilityBalance(AccountCode code, UUID owner) {
        return -rawBalance(code, owner);
    }

    /** −SUM of an account restricted to one order (e.g. deposit of order X in DEPOSIT_HELD). */
    @Transactional(readOnly = true)
    public long liabilityBalanceForOrder(AccountCode code, UUID owner, UUID orderId) {
        Long v = owner == null
                ? jdbc.queryForObject("""
                    select coalesce(sum(p.amount), 0) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                     where a.code = ? and a.owner_user_id is null and p.order_id = ?""", Long.class, code.name(), orderId)
                : jdbc.queryForObject("""
                    select coalesce(sum(p.amount), 0) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                     where a.code = ? and a.owner_user_id = ? and p.order_id = ?""", Long.class, code.name(), owner, orderId);
        return v == null ? 0 : -v;
    }

    @Transactional(readOnly = true)
    public Map<AccountCode, Long> sellerBalances(UUID seller) {
        Map<AccountCode, Long> result = new EnumMap<>(AccountCode.class);
        for (AccountCode c : List.of(AccountCode.SELLER_REVIEW_HOLD, AccountCode.SELLER_DISPUTE_HOLD,
                AccountCode.SELLER_AVAILABLE, AccountCode.SELLER_PAYOUT_PROCESSING)) {
            result.put(c, 0L);
        }
        jdbc.query("""
                select a.code, -coalesce(sum(p.amount), 0) from ledger_accounts a
                  left join ledger_postings p on p.account_id = a.id
                 where a.owner_user_id = ? group by a.code""",
                rs -> { result.put(AccountCode.valueOf(rs.getString(1)), rs.getLong(2)); }, seller);
        return result;
    }

    /** One line of a user's wallet statement: amount > 0 money in, < 0 money out. */
    public record WalletEntry(UUID transactionId, String type, UUID orderId, String reference, long amount,
                              Instant createdAt) {}

    /** Movements of the user's wallet (SELLER_AVAILABLE), newest first. */
    @Transactional(readOnly = true)
    public List<WalletEntry> walletHistory(UUID userId, int limit) {
        return jdbc.query("""
                select t.id, t.type, t.order_id, t.reference, -p.amount, t.created_at
                  from ledger_postings p
                  join ledger_accounts a on a.id = p.account_id and a.code = 'SELLER_AVAILABLE' and a.owner_user_id = ?
                  join ledger_transactions t on t.id = p.transaction_id
                 order by t.created_at desc, t.id
                 limit ?""",
                (rs, i) -> new WalletEntry((UUID) rs.getObject(1), rs.getString(2), (UUID) rs.getObject(3),
                        rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant()),
                userId, Math.max(1, Math.min(limit, 200)));
    }

    /** One page of the wallet statement, newest first. */
    @Transactional(readOnly = true)
    public com.rentgg.shared.web.PageResponse<WalletEntry> walletHistory(UUID userId, int page, int size) {
        List<WalletEntry> rows = jdbc.query("""
                select t.id, t.type, t.order_id, t.reference, -p.amount, t.created_at
                  from ledger_postings p
                  join ledger_accounts a on a.id = p.account_id and a.code = 'SELLER_AVAILABLE' and a.owner_user_id = ?
                  join ledger_transactions t on t.id = p.transaction_id
                 order by t.created_at desc, t.id
                 limit ? offset ?""",
                (rs, i) -> new WalletEntry((UUID) rs.getObject(1), rs.getString(2), (UUID) rs.getObject(3),
                        rs.getString(4), rs.getLong(5), rs.getTimestamp(6).toInstant()),
                userId, size, (long) page * size);
        Long total = jdbc.queryForObject("""
                select count(*) from ledger_postings p
                  join ledger_accounts a on a.id = p.account_id and a.code = 'SELLER_AVAILABLE' and a.owner_user_id = ?""",
                Long.class, userId);
        return com.rentgg.shared.web.PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public long totalImbalance() {
        Long v = jdbc.queryForObject("select coalesce(sum(amount), 0) from ledger_postings", Long.class);
        return v == null ? 0 : v;
    }

    @Transactional(readOnly = true)
    public List<TxView> transactionsForOrder(UUID orderId) {
        List<TxView> result = new ArrayList<>();
        Map<UUID, TxView> byId = new LinkedHashMap<>();
        jdbc.query("""
                select t.id, t.type, t.idempotency_key, t.created_at, a.code, a.owner_user_id, p.amount
                  from ledger_transactions t join ledger_postings p on p.transaction_id = t.id
                  join ledger_accounts a on a.id = p.account_id
                 where t.order_id = ? order by t.created_at, t.id, p.amount desc""", rs -> {
            UUID id = (UUID) rs.getObject(1);
            TxView tx = byId.computeIfAbsent(id, k -> {
                try {
                    TxView v = new TxView(id, rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), new ArrayList<>());
                    result.add(v);
                    return v;
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            tx.postings().add(new Posting(AccountCode.valueOf(rs.getString(5)), (UUID) rs.getObject(6), rs.getLong(7)));
        }, orderId);
        return result;
    }

    /**
     * Order lookup for finance staff, who have no access to order pages. Matches the start of the order id,
     * part of the renter's or owner's email, or part of the listing title; empty query returns the newest orders.
     */
    @Transactional(readOnly = true)
    public List<OrderSummary> searchOrders(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase();
        String like = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return jdbc.query("""
                select o.id, o.status, o.created_at, o.start_at, o.rent_amount, o.deposit_amount,
                       ru.email, ou.email, l.title, g.name,
                       (select count(*) from ledger_transactions t where t.order_id = o.id)
                  from orders o
                  join users ru on ru.id = o.renter_id
                  join users ou on ou.id = o.owner_id
                  join listings l on l.id = o.listing_id
                  join games g on g.id = o.game_id
                 where ? = ''
                    or o.id::text like ? || '%'
                    or ru.email like '%' || ? || '%'
                    or ou.email like '%' || ? || '%'
                    or lower(l.title) like '%' || ? || '%'
                    or (length(?) >= 4 and (ru.id::text like ? || '%' or ou.id::text like ? || '%'))
                    or rentgg_fold(ru.display_name) like '%' || rentgg_fold(?) || '%'
                    or rentgg_fold(ou.display_name) like '%' || rentgg_fold(?) || '%'
                 order by o.created_at desc, o.id
                 limit ?""",
                (rs, i) -> new OrderSummary((UUID) rs.getObject(1), rs.getString(2), rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant(), rs.getLong(5), rs.getLong(6), rs.getString(7),
                        rs.getString(8), rs.getString(9), rs.getString(10), rs.getInt(11)),
                q, like, like, like, like, q, like, like, like, like, Math.max(1, Math.min(limit, 50)));
    }

    public record OrderSummary(UUID id, String status, Instant createdAt, Instant startAt, long rentAmount,
                               long depositAmount, String renterEmail, String ownerEmail, String listingTitle,
                               String gameName, int transactionCount) {}

    /** Reconciliation checks of §16.3 (MVP: on-demand). */
    @Transactional(readOnly = true)
    public Map<String, Object> reconcile() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalPostingSum", totalImbalance());
        r.put("depositHeldMismatches", jdbc.queryForList("""
                select d.order_id::text as order_id, d.amount, d.deducted_amount, d.refunded_amount,
                       coalesce(-(select sum(p.amount) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                                   where a.code = 'DEPOSIT_HELD' and p.order_id = d.order_id), 0) as ledger_held
                  from deposits d
                 where d.status in ('HELD','POST_RENTAL_HOLD','DISPUTED','REFUND_PENDING','REFUNDED','SETTLED')
                   and coalesce(-(select sum(p.amount) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                                   where a.code = 'DEPOSIT_HELD' and p.order_id = d.order_id), 0)
                       <> d.amount - d.deducted_amount - d.refunded_amount"""));
        r.put("rentEscrowOpenOnFinalOrders", jdbc.queryForList("""
                select o.id::text as order_id, o.status, -sum(p.amount) as escrow
                  from orders o join ledger_postings p on p.order_id = o.id
                  join ledger_accounts a on a.id = p.account_id and a.code = 'RENT_ESCROW'
                 where o.status in ('ENDED','CANCELLED','EXPIRED','REJECTED')
                 group by o.id, o.status having sum(p.amount) <> 0"""));
        Long refundPayable = jdbc.queryForObject("""
                select coalesce(-sum(p.amount), 0) from ledger_postings p join ledger_accounts a on a.id = p.account_id
                 where a.code = 'REFUND_PAYABLE'""", Long.class);
        Long refundsOpen = jdbc.queryForObject("select coalesce(sum(amount), 0) from refunds where status in ('PENDING','FAILED')", Long.class);
        r.put("refundPayable", refundPayable);
        r.put("refundsPendingOrFailed", refundsOpen);
        r.put("checkedAt", Instant.now(clock).toString());
        return r;
    }

    public record TxView(UUID id, String type, String idempotencyKey, Instant createdAt, List<Posting> postings) {}
}
