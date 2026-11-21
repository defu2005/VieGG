package com.rentgg;

import com.rentgg.dispute.application.DisputeAppealService;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeService.Decision;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Dev Guide §3 mục 12: the order owner may appeal a decided dispute once, within 7 days. */
class DisputeAppealIT extends IntegrationTest {

    @Autowired DisputeService disputes;
    @Autowired DisputeAppealService appeals;

    private static final String REASON = "Ảnh chụp trước khi trả đơn không cho thấy hư hỏng";

    private OrderView endedOrder(Setup s, UUID renter) {
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        return o;
    }

    @Test
    void ownerAppealsOnceAfterDecisionAndNobodyElseCan() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = endedOrder(s, renter);
        clock.advance(Duration.ofHours(20));
        UUID disputeId = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_DAMAGE, "rank dropped",
                List.of("https://img.example/before.png", "https://img.example/after.png")).id();
        UUID support = user("SUPPORT");

        // Before a decision there is nothing to appeal.
        assertApi(() -> appeals.file(s.ownerId(), disputeId, REASON), "INVALID_STATE");

        advanceAndRun(Duration.ofHours(52));
        clock.advance(Duration.ofHours(18));
        disputes.resolve(support, disputeId, new Decision(true, 120_000, 0, "DAMAGE_CONFIRMED", "ok", null));

        // Renter and strangers get the same not-found answer.
        assertApi(() -> appeals.file(renter, disputeId, REASON), "NOT_FOUND");
        assertApi(() -> appeals.file(user(), disputeId, REASON), "NOT_FOUND");
        assertApi(() -> appeals.file(s.ownerId(), disputeId, "short"), "VALIDATION_ERROR");

        DisputeAppealService.Appeal a = appeals.file(s.ownerId(), disputeId, REASON);
        assertThat(a.status()).isEqualTo("PENDING");
        assertApi(() -> appeals.file(s.ownerId(), disputeId, "a second try with more words"), "APPEAL_ALREADY_FILED");

        assertThat(appeals.forDispute(disputeId)).extracting(DisputeAppealService.Appeal::reason).containsExactly(REASON);
    }

    @Test
    void appealWindowCloses_after7Days() {
        Setup s = standardListing();
        OrderView o = endedOrder(s, user());
        clock.advance(Duration.ofHours(20));
        UUID disputeId = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_DAMAGE, "rank dropped", List.of()).id();
        advanceAndRun(Duration.ofHours(52));
        clock.advance(Duration.ofHours(18));
        disputes.resolve(user("SUPPORT"), disputeId, new Decision(true, 120_000, 0, "DAMAGE_CONFIRMED", "ok", null));

        clock.advance(Duration.ofDays(8));
        assertApi(() -> appeals.file(s.ownerId(), disputeId, REASON), "APPEAL_WINDOW_CLOSED");
    }

    @Test
    void supportAcceptsAPendingAppealOnceAndTheAcceptanceIsRecorded() {
        Setup s = standardListing();
        OrderView o = endedOrder(s, user());
        clock.advance(Duration.ofHours(20));
        UUID disputeId = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_DAMAGE, "rank dropped", List.of()).id();
        UUID support = user("SUPPORT");
        advanceAndRun(Duration.ofHours(52));
        clock.advance(Duration.ofHours(18));
        disputes.resolve(support, disputeId, new Decision(true, 120_000, 0, "DAMAGE_CONFIRMED", "ok", null));
        DisputeAppealService.Appeal a = appeals.file(s.ownerId(), disputeId, REASON);

        // Dismissing needs a reason; accepting records the outcome once and emits one event.
        assertApi(() -> appeals.review(support, a.id(), false, " "), "VALIDATION_ERROR");
        DisputeAppealService.Appeal accepted = appeals.review(support, a.id(), true, "Cần quyết định lại");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertApi(() -> appeals.review(support, a.id(), false, "lần hai"), "INVALID_STATE");

        assertThat(jdbc.queryForObject("select count(*) from outbox_events where event_type = 'DisputeAppealAccepted' "
                + "and aggregate_id = ?", Integer.class, disputeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'DISPUTE_APPEAL_REVIEWED' "
                + "and entity_id = ?", Integer.class, a.id())).isEqualTo(1);
    }
}
