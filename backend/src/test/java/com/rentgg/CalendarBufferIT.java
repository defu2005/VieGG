package com.rentgg;

import com.rentgg.listing.application.ListingView;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every end mode except FORCE_AT_END keeps the account blocked for grace_minutes (10 by default) after the
 * scheduled end: the exclusion constraint, search and busy periods all have to treat that time as taken.
 */
class CalendarBufferIT extends IntegrationTest {

    private static final Duration GRACE = Duration.ofMinutes(10);

    @Test
    void graceAndStayOnline_blockTheBufferAfterScheduledEnd() {
        for (EndMode mode : List.of(EndMode.GRACE_FOR_CURRENT_MATCH, EndMode.STAY_ONLINE_UNTIL_RECLAIM)) {
            Setup s = listing(30_000, 200_000, true, mode);
            OrderView a = order(user(), s, Duration.ofHours(1), 60);
            assertThat(Duration.between(a.scheduledEndAt(), a.blockEndAt())).as(mode.name()).isEqualTo(GRACE);

            // A second booking starting at the scheduled end, or inside the buffer, is refused.
            assertApi(() -> book(s, a.scheduledEndAt(), 60), "SLOT_UNAVAILABLE");
            assertApi(() -> book(s, a.scheduledEndAt().plus(Duration.ofMinutes(5)), 60), "SLOT_UNAVAILABLE");

            // Search does not return the listing for a slot inside the buffer…
            assertThat(searchIds(s, a.scheduledEndAt(), a.scheduledEndAt().plus(Duration.ofMinutes(30))))
                    .as(mode.name()).doesNotContain(s.listingId());
            assertThat(searchIds(s, a.scheduledEndAt().plus(Duration.ofMinutes(9)), a.blockEndAt().plus(Duration.ofHours(1))))
                    .as(mode.name()).doesNotContain(s.listingId());
            // …but does right after it, and that slot can really be booked.
            assertThat(searchIds(s, a.blockEndAt(), a.blockEndAt().plus(Duration.ofHours(1)))).contains(s.listingId());
            assertThat(book(s, a.blockEndAt(), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

            // The busy calendar shown on the listing page ends at block_end_at, buffer included.
            List<Map<String, Object>> busy = listings.busyPeriods(s.accountId());
            assertThat(toInstant(busy.get(0).get("endAt"))).isEqualTo(a.blockEndAt());
        }
    }

    @Test
    void forceAtEnd_hasNoBuffer() {
        Setup s = standardListing();
        OrderView a = order(user(), s, Duration.ofHours(1), 60);
        assertThat(a.blockEndAt()).isEqualTo(a.scheduledEndAt());
        assertThat(searchIds(s, a.scheduledEndAt(), a.scheduledEndAt().plus(Duration.ofHours(1)))).contains(s.listingId());
        assertThat(book(s, a.scheduledEndAt(), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** The new order's own buffer counts too: a slot whose grace would run into the next booking is not free. */
    @Test
    void searchAccountsForTheNewOrdersOwnBuffer() {
        Setup s = listing(30_000, 200_000, true, EndMode.GRACE_FOR_CURRENT_MATCH);
        OrderView later = order(user(), s, Duration.ofHours(3), 60);
        Instant from = now().plus(Duration.ofHours(1));

        // Ends 5 minutes before `later` starts → its grace (10 minutes) would overlap.
        Instant tooLate = later.startAt().minus(Duration.ofMinutes(5));
        assertThat(searchIds(s, from, tooLate)).doesNotContain(s.listingId());
        assertApi(() -> book(s, from, (int) Duration.between(from, tooLate).toMinutes()), "SLOT_UNAVAILABLE");

        // Ends exactly one grace before `later` starts → fits.
        Instant fits = later.startAt().minus(GRACE);
        assertThat(searchIds(s, from, fits)).contains(s.listingId());
        OrderView ok = book(s, from, (int) Duration.between(from, fits).toMinutes());
        assertThat(ok.blockEndAt()).isEqualTo(later.startAt());
    }

    /** "Start on payment": the slot shrinks to [now, now + duration + grace] when it starts, buffer kept. */
    @Test
    void startOnPayment_keepsTheBufferForStayOnline() {
        Setup s = listing(30_000, 200_000, true, EndMode.STAY_ONLINE_UNTIL_RECLAIM);
        OrderView o = pay(orders.create(user(), s.listingId(), null, 60, UUID.randomUUID().toString()).order());
        assertThat(o.status()).isIn(OrderStatus.CONFIRMED, OrderStatus.IN_RENTAL);
        assertThat(Duration.between(o.scheduledEndAt(), o.blockEndAt())).isEqualTo(GRACE);
        assertApi(() -> book(s, o.scheduledEndAt(), 60), "SLOT_UNAVAILABLE");
    }

    /** STAY_ONLINE ends at scheduled_end + grace, then waits for the owner to reclaim. */
    @Test
    void stayOnline_endsAfterTheBuffer() {
        Setup s = listing(30_000, 200_000, true, EndMode.STAY_ONLINE_UNTIL_RECLAIM);
        OrderView o = paidOrder(user(), s, Duration.ofHours(1), 60);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.IN_RENTAL);
        advanceAndRun(GRACE);
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        assertThat(reload(o).actualEndAt()).isEqualTo(o.blockEndAt());
    }

    private OrderView book(Setup s, Instant startAt, int minutes) {
        return orders.create(user(), s.listingId(), startAt, minutes, UUID.randomUUID().toString()).order();
    }

    private List<UUID> searchIds(Setup s, Instant from, Instant to) {
        return listings.search(s.gameId(), null, null, from, to, 0, 50).content().stream().map(ListingView::id).toList();
    }

    private static Instant toInstant(Object v) {
        return v instanceof java.sql.Timestamp t ? t.toInstant() : ((java.time.OffsetDateTime) v).toInstant();
    }
}
