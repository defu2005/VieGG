package com.rentgg;

import com.rentgg.listing.application.AccountBlockService;
import com.rentgg.listing.application.ListingView;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.shared.error.ApiException;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Owners mark their account busy; nobody can book it then, and the calendar/search show it as taken. */
class AccountBusyBlockIT extends IntegrationTest {

    @Autowired AccountBlockService blocks;

    @Test
    void busyTime_blocksBookingSearchAndShowsOnCalendar() {
        Setup s = standardListing();
        Instant from = now().plus(Duration.ofHours(3));
        Instant to = from.plus(Duration.ofHours(2));
        List<AccountBlockService.BlockView> list = blocks.add(s.ownerId(), s.listingId(), from, to, "Tự chơi");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).note()).isEqualTo("Tự chơi");

        // Inside, or running into, the busy time: refused with the owner-specific message.
        assertThatThrownBy(() -> book(s, from.plus(Duration.ofMinutes(30)), 60))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).code()).isEqualTo("SLOT_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("Chủ account");
                });
        assertApi(() -> book(s, from.minus(Duration.ofMinutes(30)), 60), "SLOT_UNAVAILABLE");
        // Right before and right after are fine.
        assertThat(book(s, from.minus(Duration.ofHours(1)), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(book(s, to, 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        assertThat(searchIds(s, from, from.plus(Duration.ofMinutes(30)))).doesNotContain(s.listingId());

        List<Map<String, Object>> busy = listings.busyPeriods(s.accountId());
        assertThat(busy).extracting(m -> m.get("kind")).containsExactly("BOOKED", "OWNER_BUSY", "BOOKED");
        assertThat(busy.get(1)).doesNotContainKey("note");

        // Removing it frees the slot again.
        blocks.remove(s.ownerId(), s.listingId(), list.get(0).id());
        assertThat(book(s, from.plus(Duration.ofMinutes(30)), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    void busyTime_cannotCoverABookingOrAnotherBusyTime() {
        Setup s = standardListing();
        OrderView o = order(user(), s, Duration.ofHours(2), 60);
        assertApi(() -> blocks.add(s.ownerId(), s.listingId(), o.startAt().minus(Duration.ofMinutes(10)),
                o.startAt().plus(Duration.ofMinutes(10)), null), "BLOCK_OVERLAPS_ORDER");

        Instant from = o.blockEndAt().plus(Duration.ofHours(1));
        blocks.add(s.ownerId(), s.listingId(), from, from.plus(Duration.ofHours(1)), null);
        assertApi(() -> blocks.add(s.ownerId(), s.listingId(), from.plus(Duration.ofMinutes(30)),
                from.plus(Duration.ofHours(2)), null), "BLOCK_OVERLAP");
        // Back to back is allowed.
        assertThat(blocks.add(s.ownerId(), s.listingId(), from.plus(Duration.ofHours(1)), from.plus(Duration.ofHours(2)), null))
                .hasSize(2);
    }

    @Test
    void validation_andOwnership() {
        Setup s = standardListing();
        Instant t = now();
        assertApi(() -> blocks.add(s.ownerId(), s.listingId(), t.plus(Duration.ofHours(2)), t.plus(Duration.ofHours(1)), null),
                "VALIDATION_ERROR");
        assertApi(() -> blocks.add(s.ownerId(), s.listingId(), t.minus(Duration.ofHours(2)), t.minus(Duration.ofHours(1)), null),
                "BLOCK_IN_PAST");
        assertApi(() -> blocks.add(s.ownerId(), s.listingId(), t, t.plus(Duration.ofDays(91)), null), "BLOCK_TOO_LONG");
        assertApi(() -> blocks.add(user(), s.listingId(), t.plus(Duration.ofHours(1)), t.plus(Duration.ofHours(2)), null),
                "NOT_FOUND");

        // A start in the past means "from now".
        List<AccountBlockService.BlockView> list =
                blocks.add(s.ownerId(), s.listingId(), t.minus(Duration.ofHours(1)), t.plus(Duration.ofHours(1)), null);
        assertThat(list.get(0).startAt()).isEqualTo(t);
        assertApi(() -> blocks.remove(s.ownerId(), s.listingId(), UUID.randomUUID()), "NOT_FOUND");
    }

    private OrderView book(Setup s, Instant startAt, int minutes) {
        return orders.create(user(), s.listingId(), startAt, minutes, UUID.randomUUID().toString()).order();
    }

    private List<UUID> searchIds(Setup s, Instant from, Instant to) {
        return listings.search(s.gameId(), null, null, from, to, 0, 50).content().stream().map(ListingView::id).toList();
    }
}
