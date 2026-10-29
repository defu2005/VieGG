package com.rentgg.jobs;

import com.rentgg.ledger.application.LedgerService;
import com.rentgg.shared.outbox.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Gauges of §16.2 that are read from the database on scrape. */
@Component
public class BusinessMetrics {

    private final MeterRegistry meters;
    private final LedgerService ledger;
    private final OutboxRepository outbox;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BusinessMetrics(MeterRegistry meters, LedgerService ledger, OutboxRepository outbox, JdbcTemplate jdbc,
                           Clock clock) {
        this.meters = meters;
        this.ledger = ledger;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostConstruct
    void register() {
        Gauge.builder("rentgg_ledger_imbalance", ledger, l -> safe(l::totalImbalance)).register(meters);
        Gauge.builder("rentgg_outbox_pending", outbox, o -> safe(o::countPending)).register(meters);
        Gauge.builder("rentgg_outbox_oldest_age_seconds", outbox, o -> safe(() -> {
            Instant oldest = o.oldestPending();
            return oldest == null ? 0 : Duration.between(oldest, clock.instant()).toSeconds();
        })).register(meters);
        for (String type : new String[]{"OWNER_DAMAGE", "ACCOUNT_TAKEOVER", "RENTER_LOGIN_FAILED", "RENTER_NOT_AS_DESCRIBED"}) {
            Gauge.builder("rentgg_disputes_open", jdbc, j -> safe(() -> count(
                    "select count(*) from disputes where status = 'OPEN' and type = ?", type)))
                    .tag("type", type).register(meters);
        }
        for (String state : new String[]{"PENDING_VERIFICATION", "AVAILABLE", "IN_USE", "AWAITING_RECLAIM",
                "ROTATION_PENDING", "ROTATION_OVERDUE", "SUSPENDED_SECURITY_REVIEW"}) {
            Gauge.builder("rentgg_accounts_by_security_state", jdbc, j -> safe(() -> count(
                    "select count(*) from game_accounts where security_state = ?", state)))
                    .tag("state", state).register(meters);
        }
        for (String status : new String[]{"HELD", "POST_RENTAL_HOLD", "DISPUTED", "REFUND_PENDING"}) {
            Gauge.builder("rentgg_deposits_amount_by_status", jdbc, j -> safe(() -> count(
                    "select coalesce(sum(amount), 0) from deposits where status = ?", status)))
                    .tag("status", status).register(meters);
        }
        Gauge.builder("rentgg_approvals_pending", jdbc, j -> safe(() -> count(
                "select count(*) from approval_requests where status = ?", "PENDING"))).register(meters);
    }

    private long count(String sql, String arg) {
        Long v = jdbc.queryForObject(sql, Long.class, arg);
        return v == null ? 0 : v;
    }

    private static double safe(java.util.function.LongSupplier s) {
        try {
            return s.getAsLong();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
