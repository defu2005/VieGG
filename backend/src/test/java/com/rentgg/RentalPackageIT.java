package com.rentgg;

import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.PricingTier;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Listing rental packages, "start right after payment" orders, and bookings for a later time. */
class RentalPackageIT extends IntegrationTest {

    private Setup packageListing(boolean autoAccept) {
        UUID gameId = game();
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID account = gameAccounts.create(owner, gameId, "VN", "player_" + owner.toString().substring(0, 6),
                "secret-pass-1", true, true, true).id();
        gameAccounts.verifyRecovery(admin, account);
        UUID listing = listings.create(owner, account, new ListingService.Content("Acc", "desc", Map.of(), 0,
                List.of(new PricingTier(180, 60_000), new PricingTier(60, 25_000)), 200_000, autoAccept,
                EndMode.FORCE_AT_END)).id();
        listings.submit(owner, listing);
        listings.approve(admin, listing);
        return new Setup(gameId, owner, account, listing);
    }

    private OrderView orderNow(UUID renter, Setup s, int minutes) {
        return orders.create(renter, s.listingId(), null, minutes, UUID.randomUUID().toString()).order();
    }

    @Test
    void packagesSetThePriceAndOnlyOfferedDurationsCanBeBooked() {
        Setup s = packageListing(true);
        var view = listings.get(s.listingId());
        assertThat(view.pricingTiers()).extracting(PricingTier::durationMinutes).containsExactly(60, 180);
        assertThat(view.pricePerHour()).isEqualTo(25_000);

        OrderView o = order(user(), s, Duration.ofDays(1), 180);
        assertThat(o.rentAmount()).isEqualTo(60_000);
        assertApi(() -> order(user(), s, Duration.ofDays(2), 120), "DURATION_NOT_OFFERED");
    }

    @Test
    void startNowOrderIsUsableRightAfterPayment() {
        Setup s = packageListing(true);
        OrderView o = orderNow(user(), s, 60);
        assertThat(o.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        clock.advance(Duration.ofMinutes(5));
        OrderView paid = pay(o);
        assertThat(paid.status()).isEqualTo(OrderStatus.IN_RENTAL);
        assertThat(paid.startAt()).isEqualTo(now());
        assertThat(paid.scheduledEndAt()).isEqualTo(now().plus(Duration.ofMinutes(60)));
        assertThat(paid.blockEndAt()).isEqualTo(paid.scheduledEndAt());
        assertThat(totalLedger()).isZero();
    }

    @Test
    void bookingForTomorrowIsPaidNowButOnlyUsableAtTheBookedTime() {
        Setup s = packageListing(true);
        OrderView o = order(user(), s, Duration.ofDays(1), 60);
        OrderView paid = pay(o);
        assertThat(paid.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(paid.startAt()).isEqualTo(o.startAt());

        advanceAndRun(Duration.ofHours(23));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.CONFIRMED);
        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.IN_RENTAL);
    }

    @Test
    void startNowOrderNeedingApprovalStartsWhenApprovedAndCanBeCancelledBefore() {
        Setup s = packageListing(false);
        OrderView o = pay(orderNow(user(), s, 60));
        assertThat(o.status()).isEqualTo(OrderStatus.AWAITING_OWNER_APPROVAL);

        clock.advance(Duration.ofMinutes(10));
        OrderView approved = orders.approve(s.ownerId(), o.id());
        assertThat(approved.status()).isEqualTo(OrderStatus.IN_RENTAL);
        assertThat(approved.startAt()).isEqualTo(now());
        assertThat(approved.scheduledEndAt()).isEqualTo(now().plus(Duration.ofMinutes(60)));

        // Another one, cancelled by the renter while waiting: full refund.
        OrderView other = pay(orderNow(user(), packageListing(false), 60));
        OrderView cancelled = orders.cancel(other.renterId(), other.id());
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(totalLedger()).isZero();
    }
}
