package com.rentgg;

import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeService.Decision;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dev Guide §3 mục 14: an OPEN dispute past its deadline is marked once, and only if still OPEN. */
class DisputeDeadlineIT extends IntegrationTest {

    @Autowired DisputeService disputes;

    private OrderView endedOrder(Setup s, UUID renter) {
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        return o;
    }

    private Timestamp deadline(UUID disputeId) {
        return jdbc.queryForObject("select deadline_at from disputes where id = ?", Timestamp.class, disputeId);
    }

    private Timestamp overdueAt(UUID disputeId) {
        return jdbc.queryForObject("select deadline_overdue_at from disputes where id = ?", Timestamp.class, disputeId);
    }

    @Test
    void openDisputePastDeadlineIsMarkedOnceAndDecidedOnesAreNot() {
        Setup s = standardListing();
        OrderView o = endedOrder(s, user());
        clock.advance(Duration.ofHours(20));
        UUID open = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_DAMAGE, "rank dropped", List.of()).id();
        assertThat(overdueAt(open)).isNull();

        // Before the deadline nothing is marked.
        clock.set(deadline(open).toInstant().minus(Duration.ofMinutes(1)));
        runJobs();
        assertThat(overdueAt(open)).isNull();

        // Past the deadline: marked by the job, with one outbox event.
        clock.set(deadline(open).toInstant().plus(Duration.ofMinutes(1)));
        runJobs();
        Timestamp first = overdueAt(open);
        assertThat(first).isNotNull();
        long events = jdbc.queryForObject("select count(*) from outbox_events where event_type = 'DisputeDeadlineOverdue' and aggregate_id = ?",
                Long.class, open);
        assertThat(events).isEqualTo(1);

        // Running again changes nothing.
        clock.advance(Duration.ofHours(1));
        runJobs();
        assertThat(overdueAt(open)).isEqualTo(first);

        // A dispute decided before its deadline is never marked.
        Setup s2 = standardListing();
        OrderView o2 = endedOrder(s2, user());
        clock.advance(Duration.ofHours(20));
        UUID other = disputes.open(s2.ownerId(), o2.id(), DisputeType.OWNER_DAMAGE, "second", List.of()).id();
        clock.advance(Duration.ofHours(72));
        disputes.resolve(user("SUPPORT"), other, new Decision(false, 0, 0, "NO_DAMAGE", "ok", null));
        clock.set(deadline(other).toInstant().plus(Duration.ofDays(2)));
        runJobs();
        assertThat(overdueAt(other)).isNull();
    }
}
