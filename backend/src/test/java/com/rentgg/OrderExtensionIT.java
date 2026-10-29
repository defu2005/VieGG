package com.rentgg;

import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.order.application.OrderExtensionService;
import com.rentgg.order.application.OrderExtensionService.Checkout;
import com.rentgg.order.application.OrderExtensionService.Option;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderExtension;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-17 (P1): extending a running rental without a new booking, deposit, approval or handover. */
class OrderExtensionIT extends IntegrationTest {

    @Autowired OrderExtensionService extensions;

    /** Rental that started right now (paid, auto-accepted). 30.000đ/h, deposit 200.000đ, FORCE_AT_END. */
    private OrderView running(UUID renter, Setup s, int minutes) {
        OrderView o = pay(orders.create(renter, s.listingId(), null, minutes, UUID.randomUUID().toString()).order());
        assertThat(o.status()).isEqualTo(OrderStatus.IN_RENTAL);
        return o;
    }

    /** Tops up the wallet with the extension price, then requests it (paid from the wallet right away). */
    private Checkout extend(OrderView o, int minutes) {
        extensions.options(o.renterId(), o.id()).options().stream().filter(x -> x.durationMinutes() == minutes)
                .findFirst().ifPresent(x -> topup(o.renterId(), x.price()));
        return extensions.request(o.renterId(), o.id(), minutes, UUID.randomUUID().toString());
    }

    private Option option(OrderView o, int minutes) {
        return extensions.options(o.renterId(), o.id()).options().stream()
                .filter(x -> x.durationMinutes() == minutes).findFirst().orElseThrow();
    }

    @Test
    void paidExtension_movesTheEnd_addsRentOnly_andEndsLater() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = running(renter, s, 60);
        var oldEnd = o.scheduledEndAt();

        var opts = extensions.options(renter, o.id());
        assertThat(opts.allowed()).isTrue();
        assertThat(opts.nextBookingAt()).isNull();
        assertThat(option(o, 60).price()).isEqualTo(30_000);

        clock.advance(Duration.ofMinutes(40));
        // Empty wallet: the request is refused for lack of balance, but the time stays reserved for the renter.
        assertApi(() -> extensions.request(renter, o.id(), 60, UUID.randomUUID().toString()), "INSUFFICIENT_WALLET_BALANCE");
        var pending = extensions.options(renter, o.id()).pending();
        assertThat(pending.status()).isEqualTo(OrderExtension.Status.PENDING_PAYMENT);
        // Not granted before payment.
        assertThat(reload(o).scheduledEndAt()).isEqualTo(oldEnd);
        // …but already reserved: nobody can book the extra hour meanwhile.
        assertApi(() -> order(user(), s, Duration.ofMinutes(20), 60), "SLOT_UNAVAILABLE");

        topup(renter, 30_000);
        Checkout c = extensions.pay(renter, o.id(), pending.id());
        assertThat(c.amount()).isEqualTo(30_000); // no second deposit
        assertThat(c.extension().status()).isEqualTo(OrderExtension.Status.PAID);
        assertThat(walletBalance(renter)).isZero();
        OrderView extended = reload(o);
        assertThat(extended.status()).isEqualTo(OrderStatus.IN_RENTAL);
        assertThat(extended.scheduledEndAt()).isEqualTo(oldEnd.plus(Duration.ofHours(1)));
        assertThat(extended.blockEndAt()).isEqualTo(extended.scheduledEndAt());
        assertThat(extended.rentAmount()).isEqualTo(60_000);
        assertThat(extended.platformFee()).isEqualTo(6_000);
        assertThat(extended.depositAmount()).isEqualTo(200_000);
        assertThat(extended.extendedMinutes()).isEqualTo(60);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.id())).isEqualTo(60_000);
        assertThat(totalLedger()).isZero();

        // The old end passes: still renting. The new end passes: ended, all rent goes to the seller hold.
        advanceAndRun(Duration.ofMinutes(25));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.IN_RENTAL);
        advanceAndRun(Duration.ofMinutes(60));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.id())).isZero();
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.SELLER_REVIEW_HOLD, s.ownerId(), o.id())).isEqualTo(54_000);

        // Deposit refund goes against the booking's payment (the only one that included the deposit).
        advanceAndRun(Duration.ofHours(73));
        UUID bookingIntent = payments.intentsForOrder(o.id()).get(0).id();
        List<UUID> depositRefundIntents = jdbc.queryForList(
                "select payment_intent_id from refunds where order_id = ? and kind = 'DEPOSIT'", UUID.class, o.id());
        assertThat(depositRefundIntents).containsOnly(bookingIntent);
        // …and lands in the renter's wallet.
        assertThat(walletBalance(renter)).isEqualTo(200_000);
        assertThat(totalLedger()).isZero();
    }

    @Test
    void nextBooking_limitsTheChoices() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = running(renter, s, 60);
        // Someone booked the account starting one hour after this rental ends.
        order(user(), s, Duration.ofHours(2), 60);

        assertThat(extensions.options(renter, o.id()).nextBookingAt()).isEqualTo(o.scheduledEndAt().plus(Duration.ofHours(1)));
        assertThat(option(o, 60).available()).isTrue();
        assertThat(option(o, 120).available()).isFalse();
        assertApi(() -> extend(o, 120), "SLOT_UNAVAILABLE");

        Checkout c = extend(o, 60);
        assertThat(c.extension().status()).isEqualTo(OrderExtension.Status.PAID);
        assertThat(reload(o).scheduledEndAt()).isEqualTo(o.scheduledEndAt().plus(Duration.ofHours(1)));
    }

    @Test
    void graceMode_keepsTheBufferAfterTheNewEnd() {
        Setup s = listing(30_000, 200_000, true, EndMode.GRACE_FOR_CURRENT_MATCH);
        UUID renter = user();
        OrderView o = running(renter, s, 60);
        // Next booking starts 55 minutes after the current block end: a 60-minute extension needs 60 + 10 grace.
        order(user(), s, Duration.between(now(), o.blockEndAt()).plus(Duration.ofMinutes(55)), 60);
        assertThat(option(o, 30).available()).isTrue();
        assertThat(option(o, 60).available()).isFalse();

        Checkout c = extend(o, 30);
        assertThat(c.extension().status()).isEqualTo(OrderExtension.Status.PAID);
        OrderView extended = reload(o);
        assertThat(Duration.between(extended.scheduledEndAt(), extended.blockEndAt())).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void unpaidExtension_expires_releasesTheSlot_andCannotBePaidLate() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = running(renter, s, 60);
        assertApi(() -> extensions.request(renter, o.id(), 60, UUID.randomUUID().toString()), "INSUFFICIENT_WALLET_BALANCE");
        var pending = extensions.options(renter, o.id()).pending();

        advanceAndRun(Duration.ofMinutes(16)); // payment hold is 15 minutes
        assertThat(extensions.options(renter, o.id()).pending()).isNull();
        assertThat(reload(o).scheduledEndAt()).isEqualTo(o.scheduledEndAt());
        // The reserved hour is free again.
        assertThat(order(user(), s, Duration.ofMinutes(44), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);

        // Topping up afterwards does not let the expired extension be paid; the wallet keeps the money.
        topup(renter, 30_000);
        assertApi(() -> extensions.pay(renter, o.id(), pending.id()), "INVALID_STATE");
        assertThat(walletBalance(renter)).isEqualTo(30_000);
        assertThat(reload(o).rentAmount()).isEqualTo(30_000);
        assertThat(totalLedger()).isZero();
    }

    @Test
    void onePendingAtATime_cancelReleasesIt() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = running(renter, s, 60);
        assertApi(() -> extensions.request(renter, o.id(), 60, UUID.randomUUID().toString()), "INSUFFICIENT_WALLET_BALANCE");
        var pending = extensions.options(renter, o.id()).pending();
        assertApi(() -> extend(o, 30), "EXTENSION_PENDING");

        extensions.cancel(renter, o.id(), pending.id());
        assertThat(order(user(), s, Duration.ofMinutes(60), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // The next booking now starts right at the end: no room left.
        assertThat(option(o, 30).available()).isFalse();
        assertApi(() -> extend(o, 30), "SLOT_UNAVAILABLE");
    }

    @Test
    void refused_whenNotRunning_afterEnd_overMaxDuration_orNotRenter() {
        Setup s = standardListing();
        UUID renter = user();

        OrderView later = paidOrder(renter, s, Duration.ofHours(5), 60); // CONFIRMED, not started
        assertApi(() -> extend(later, 60), "INVALID_STATE");

        OrderView o = running(renter, s, 60);
        assertApi(() -> extensions.request(user(), o.id(), 60, "k"), "NOT_FOUND");
        assertThat(extensions.options(s.ownerId(), o.id()).allowed()).isFalse();
        assertApi(() -> extend(o, 45), "DURATION_NOT_OFFERED");

        clock.advance(Duration.ofMinutes(60));
        assertApi(() -> extend(o, 60), "RENTAL_TIME_OVER");

        UUID shortGame = game(p -> new GamePolicy(p.name(), p.publisher(), p.tosUrl(), p.handoverMode(),
                p.passwordChangeKicksSessions(), p.depositMin(), p.depositMax(), p.depositHoldHours(),
                p.sellerHoldHours(), p.sellerHoldHoursHighRisk(), p.complaintWindowMinutes(), p.minDurationMinutes(),
                120, p.graceMinutes(), p.platformFeeBps(), p.renterFreeCancelMinutes(), p.lateCancelRefundBps(),
                p.ownerFreeCancelHours(), p.minAge(), p.riskNotes(), p.riskLevel()));
        Setup s2 = listing(shortGame, 30_000, 200_000, true, EndMode.FORCE_AT_END);
        OrderView capped = running(renter, s2, 120);
        assertThat(extensions.options(renter, capped.id()).blockedCode()).isEqualTo("MAX_DURATION_REACHED");
        assertApi(() -> extend(capped, 30), "MAX_DURATION_REACHED");
    }
}
