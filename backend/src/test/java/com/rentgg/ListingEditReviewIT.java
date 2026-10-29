package com.rentgg;

import com.rentgg.listing.application.ListingService.Content;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Editing an ACTIVE listing: only renter-facing content needs review again; the owner's own terms apply at once. */
class ListingEditReviewIT extends IntegrationTest {

    @Test
    void priceAndTermsChangeStaysActiveButIsAudited() {
        Setup s = standardListing();
        var updated = listings.update(s.ownerId(), s.listingId(), null,
                new Content("Acc", "desc", Map.of(), 45_000, 300_000, false, EndMode.FORCE_AT_END));
        assertThat(updated.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(updated.pricePerHour()).isEqualTo(45_000);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'LISTING_TERMS_CHANGED' and entity_id = ?",
                Integer.class, s.listingId())).isEqualTo(1);
    }

    @Test
    void titleChangeGoesBackToReview() {
        Setup s = standardListing();
        var updated = listings.update(s.ownerId(), s.listingId(), null,
                new Content("New title", "desc", Map.of(), 30_000, 200_000, true, EndMode.FORCE_AT_END));
        assertThat(updated.status()).isEqualTo(ListingStatus.PENDING_REVIEW);
    }
}
