package com.rentgg;

import com.rentgg.jobs.Jobs;
import com.rentgg.legal.application.LegalNoticeService;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-20: a notice moves OPEN → RESPONDED → CLOSED; staff are reminded once before the deadline and told once after it. */
class LegalNoticeLifecycleIT extends IntegrationTest {

    @Autowired LegalNoticeService legal;

    private UUID record(UUID by, Instant receivedAt) {
        return legal.record(by, new LegalNoticeService.Command(null, "Nhà phát hành", "REF-1", "Yêu cầu gỡ nội dung",
                "Nội dung thông báo", receivedAt)).id();
    }

    private int events(String type, UUID id) {
        return jdbc.queryForObject("select count(*) from outbox_events where event_type = ? and aggregate_id = ?",
                Integer.class, type, id);
    }

    @Test
    void transitionsFollowOpenRespondedClosedAndEachMoveIsAudited() {
        UUID support = user("SUPPORT");
        UUID id = record(support, now());

        assertApi(() -> legal.transition(support, id, "CLOSED", null), "INVALID_STATE");
        assertThat(legal.transition(support, id, "RESPONDED", "Đã gửi phản hồi").status()).isEqualTo("RESPONDED");
        assertApi(() -> legal.transition(support, id, "RESPONDED", null), "INVALID_STATE");
        assertThat(legal.transition(support, id, "CLOSED", null).status()).isEqualTo("CLOSED");
        assertApi(() -> legal.transition(support, id, "OPEN", null), "INVALID_STATE");
        assertApi(() -> legal.transition(support, id, "RESPONDED", "x".repeat(1001)), "VALIDATION_ERROR");

        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LEGAL_NOTICE_TRANSITIONED' "
                + "and entity_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void reminderIsSentOnceWhenTheDeadlineIsNear() {
        // Received 28 days ago: the 30-day deadline is 2 days away.
        UUID id = record(user("SUPPORT"), now().minus(Duration.ofDays(28)));

        jobs.markLegalNoticeDeadlines();
        jobs.markLegalNoticeDeadlines();

        assertThat(events("LegalNoticeDeadlineSoon", id)).isEqualTo(1);
        assertThat(events("LegalNoticeOverdue", id)).isZero();
    }

    @Test
    void openNoticeIsMarkedOverdueOnceAfterTheDeadline() {
        UUID id = record(user("SUPPORT"), now());

        clock.advance(Duration.ofDays(31));
        jobs.markLegalNoticeDeadlines();
        jobs.markLegalNoticeDeadlines();

        assertThat(events("LegalNoticeOverdue", id)).isEqualTo(1);
    }

    @Test
    void respondedNoticeIsNotMarkedOverdue() {
        UUID support = user("SUPPORT");
        UUID id = record(support, now());
        legal.transition(support, id, "RESPONDED", null);

        clock.advance(Duration.ofDays(31));
        jobs.markLegalNoticeDeadlines();

        assertThat(events("LegalNoticeOverdue", id)).isZero();
    }
}
