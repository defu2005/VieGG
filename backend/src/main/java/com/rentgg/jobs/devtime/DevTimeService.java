package com.rentgg.jobs.devtime;

import com.rentgg.config.RentGgProperties;
import com.rentgg.jobs.Jobs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dev time travel. Moving forward "with steps" stops at every job deadline on the way (payment expiry, rental
 * start/end, reminders, seller hold, deposit release...) and runs all jobs there, so chained steps get the same
 * timestamps they would get in real life instead of all firing at the destination.
 */
@Service
@Profile("dev")
public class DevTimeService {

    private static final Logger log = LoggerFactory.getLogger(DevTimeService.class);
    private static final int MAX_STEPS = 500;

    /** Mirrors the job queries (OrderRepository, DepositRepository, ...): what becomes due, and when. */
    private static final String DEADLINES = """
            select * from (
              select 'PAYMENT_EXPIRES' kind, o.id order_id, o.payment_expires_at at from orders o
               where o.status = 'PENDING_PAYMENT'
              union all
              select 'APPROVAL_EXPIRES', o.id, o.approval_expires_at from orders o
               where o.status = 'AWAITING_OWNER_APPROVAL' and o.approval_expires_at is not null
              union all
              select 'RENTAL_STARTS', o.id, o.start_at from orders o where o.status = 'CONFIRMED'
              union all
              select 'ENDING_REMINDER', o.id, o.scheduled_end_at - make_interval(mins => :reminderMinutes) from orders o
               where o.status = 'IN_RENTAL' and o.ending_reminder_sent_at is null
              union all
              select 'RENTAL_ENDS', o.id, o.block_end_at from orders o where o.status = 'IN_RENTAL'
              union all
              select 'EXTENSION_PAYMENT_EXPIRES', e.order_id, e.payment_expires_at from order_extensions e
               where e.status = 'PENDING_PAYMENT'
              union all
              select 'SELLER_HOLD_RELEASE', o.id, o.seller_hold_until from orders o
               where o.seller_hold_until is not null and o.seller_released_at is null
              union all
              select 'DEPOSIT_RELEASE', d.order_id, d.hold_until from deposits d
               where d.status = 'POST_RENTAL_HOLD' and d.hold_until is not null
              union all
              select 'ROTATION_OVERDUE', null, g.rotation_due_at from game_accounts g
               where g.security_state = 'ROTATION_PENDING' and g.rotation_due_at is not null
            ) x where x.at > :after
            """;

    private final DevClock clock;
    private final Jobs jobs;
    private final NamedParameterJdbcTemplate jdbc;
    private final RentGgProperties props;

    public DevTimeService(DevClock clock, Jobs jobs, NamedParameterJdbcTemplate jdbc, RentGgProperties props) {
        this.clock = clock;
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.props = props;
    }

    public record Deadline(String kind, UUID orderId, String listingTitle, UUID renterId, UUID ownerId, Instant at) {}

    public record TravelResult(Instant from, Instant to, int steps) {}

    /** Upcoming job deadlines, earliest first. */
    public List<Deadline> upcoming(int limit) {
        String sql = "select x.kind, x.order_id, x.at, l.title, o.renter_id, o.owner_id from (" + DEADLINES + ") x"
                + " left join orders o on o.id = x.order_id left join listings l on l.id = o.listing_id"
                + " order by x.at limit :limit";
        return jdbc.query(sql, params(clock.instant()).addValue("limit", limit), (rs, i) -> new Deadline(
                rs.getString("kind"),
                rs.getObject("order_id", UUID.class),
                rs.getString("title"),
                rs.getObject("renter_id", UUID.class),
                rs.getObject("owner_id", UUID.class),
                rs.getTimestamp("at").toInstant()));
    }

    /**
     * Moves business time to {@code target}. With {@code stepThroughDeadlines}, stops at each job deadline on the
     * way and runs the jobs there; otherwise jumps directly. Jobs always run once at the destination when
     * {@code runJobs} is set.
     */
    public synchronized TravelResult travelTo(Instant target, boolean stepThroughDeadlines, boolean runJobs) {
        Instant from = clock.instant();
        int steps = 0;
        if (stepThroughDeadlines && runJobs && target.isAfter(from)) {
            Instant cursor = from;
            while (steps < MAX_STEPS) {
                Instant next = nextDeadline(cursor, target);
                if (next == null) {
                    break;
                }
                clock.set(next);
                jobs.runAll();
                cursor = next;
                steps++;
            }
            if (steps == MAX_STEPS) {
                log.warn("DEV CLOCK: stopped stepping after {} deadlines, jumping to the target", MAX_STEPS);
            }
        }
        clock.set(target);
        if (runJobs) {
            jobs.runAll();
        }
        log.warn("DEV CLOCK: moved business time {} -> {} ({} deadline steps)", from, target, steps);
        return new TravelResult(from, target, steps);
    }

    private Instant nextDeadline(Instant after, Instant until) {
        String sql = "select min(x.at) from (" + DEADLINES + ") x where x.at <= :until";
        Timestamp t = jdbc.queryForObject(sql, params(after).addValue("until", Timestamp.from(until)), Timestamp.class);
        return t == null ? null : t.toInstant();
    }

    private MapSqlParameterSource params(Instant after) {
        return new MapSqlParameterSource()
                .addValue("after", Timestamp.from(after))
                .addValue("reminderMinutes", props.order().endingReminderMinutes());
    }
}
