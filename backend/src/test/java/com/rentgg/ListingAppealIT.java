package com.rentgg;

import com.rentgg.listing.application.ListingAppealService;
import com.rentgg.listing.application.ListingAppealService.Appeal;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-05: the listing owner appeals a rejection or suspension once, within 7 days; staff accept or dismiss. */
class ListingAppealIT extends IntegrationTest {

    @Autowired ListingAppealService appeals;

    private static final String REASON = "Ảnh và mô tả đã đúng, xin xét lại quyết định này";

    private UUID decisionOf(UUID listingId) {
        return jdbc.queryForObject("select id from listing_moderation_decisions where listing_id = ? "
                + "order by decided_at desc limit 1", UUID.class, listingId);
    }

    @Test
    void ownerAppealsOnceAndAcceptedAppealReopensTheListingForReview() {
        Setup s = standardListing();
        UUID admin = user("ADMIN");
        listings.suspend(admin, s.listingId(), "SCAM_SUSPECTED");
        UUID decision = decisionOf(s.listingId());
        UUID stranger = user();

        // Only the owner sees the decision; strangers get the same not-found answer as for a missing decision.
        assertApi(() -> appeals.file(stranger, decision, REASON), "NOT_FOUND");
        assertApi(() -> appeals.file(s.ownerId(), decision, "short"), "VALIDATION_ERROR");

        Appeal a = appeals.file(s.ownerId(), decision, REASON);
        assertThat(a.status()).isEqualTo("PENDING");
        assertApi(() -> appeals.file(s.ownerId(), decision, "a second try with more words"), "APPEAL_ALREADY_FILED");

        // Staff: dismissal needs a reason; acceptance reopens the listing for review, not publish.
        assertApi(() -> appeals.review(admin, a.id(), false, " "), "VALIDATION_ERROR");
        Appeal accepted = appeals.review(admin, a.id(), true, "Đã kiểm tra lại tài khoản");
        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(listings.get(s.listingId()).status()).isEqualTo(ListingStatus.PENDING_REVIEW);
        assertApi(() -> appeals.review(admin, a.id(), true, null), "INVALID_STATE");

        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LISTING_APPEAL_REVIEWED' "
                + "and entity_id = ?", Integer.class, a.id())).isEqualTo(1);
        assertThat(appeals.forDecision(decision)).extracting(Appeal::status).containsExactly("ACCEPTED");
    }

    @Test
    void rejectedListingCanBeAppealedAndDismissedWithAReason() {
        Setup s = standardListing();
        UUID admin = user("ADMIN");
        listings.suspend(admin, s.listingId(), "POLICY");
        UUID first = decisionOf(s.listingId());
        appeals.file(s.ownerId(), first, REASON);
        appeals.review(admin, appeals.forDecision(first).get(0).id(), true, "xem lại");

        listings.reject(admin, s.listingId(), "Ảnh chụp chưa rõ");
        UUID rejection = decisionOf(s.listingId());
        assertThat(jdbc.queryForObject("select kind from listing_moderation_decisions where id = ?", String.class, rejection))
                .isEqualTo("REJECTED");
        Appeal a = appeals.file(s.ownerId(), rejection, REASON);

        Appeal dismissed = appeals.review(admin, a.id(), false, "Ảnh vẫn chưa đạt yêu cầu");
        assertThat(dismissed.status()).isEqualTo("DISMISSED");
        assertThat(listings.get(s.listingId()).status()).isEqualTo(ListingStatus.REJECTED);
    }

    @Test
    void appealWindowCloses_after7Days() {
        Setup s = standardListing();
        UUID admin = user("ADMIN");
        listings.suspend(admin, s.listingId(), "POLICY");
        UUID decision = decisionOf(s.listingId());

        clock.advance(Duration.ofDays(8));
        assertApi(() -> appeals.file(s.ownerId(), decision, REASON), "APPEAL_WINDOW_CLOSED");
    }

    @Test
    void ownerSeesHisDecisionsAndTheStatusOfHisAppeal() {
        Setup s = standardListing();
        UUID admin = user("ADMIN");
        listings.suspend(admin, s.listingId(), "POLICY");
        UUID decision = decisionOf(s.listingId());
        appeals.file(s.ownerId(), decision, REASON);

        List<Map<String, Object>> rows = listings.decisionsOf(s.ownerId(), s.listingId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("kind", "SUSPENDED").containsEntry("appeal_status", "PENDING");
        assertApi(() -> listings.decisionsOf(user(), s.listingId()), "NOT_FOUND");
    }
}
