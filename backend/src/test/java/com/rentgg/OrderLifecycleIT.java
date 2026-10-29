package com.rentgg;

import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.catalog.domain.HandoverMode;
import com.rentgg.deposit.domain.DepositStatus;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.CancelReason;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import com.rentgg.wallet.application.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderLifecycleIT extends IntegrationTest {

    @Autowired WalletService wallet;

    /** Example 1 of §7.4, end to end, with the balances of the table after each step (T4). */
    @Test
    void normalOrder_matchesLedgerExample1() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = order(renter, s, Duration.ofHours(1), 180);
        assertThat(o.rentAmount()).isEqualTo(90_000);
        assertThat(o.platformFee()).isEqualTo(9_000);
        assertThat(o.sellerNet()).isEqualTo(81_000);
        assertThat(o.amountToPay()).isEqualTo(290_000);

        o = pay(o);
        assertThat(o.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.HELD);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.id())).isEqualTo(90_000);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.DEPOSIT_HELD, null, o.id())).isEqualTo(200_000);

        advanceAndRun(Duration.ofHours(1));
        o = reload(o);
        assertThat(o.status()).isEqualTo(OrderStatus.IN_RENTAL);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.IN_USE);

        advanceAndRun(Duration.ofHours(3));
        o = reload(o);
        assertThat(o.status()).isEqualTo(OrderStatus.ENDED);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.ROTATION_PENDING);
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.POST_RENTAL_HOLD);
        assertThat(wallet.balances(s.ownerId()).reviewHold()).isEqualTo(81_000);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.id())).isZero();

        advanceAndRun(Duration.ofHours(72));
        var b = wallet.balances(s.ownerId());
        assertThat(b.reviewHold()).isZero();
        assertThat(b.available()).isEqualTo(81_000);
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.REFUNDED);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.DEPOSIT_HELD, null, o.id())).isZero();

        var w = wallet.requestWithdrawal(s.ownerId(), 81_000,
                new WalletService.BankAccount("VCB", "0123456789", "Owner"), UUID.randomUUID().toString()).withdrawal();
        assertThat(wallet.balances(s.ownerId()).payoutProcessing()).isEqualTo(81_000);
        wallet.markPaid(user("FINANCE"), w.getId(), "BANK-REF-1");
        b = wallet.balances(s.ownerId());
        assertThat(b.available()).isZero();
        assertThat(b.payoutProcessing()).isZero();
        assertThat(totalLedger()).isZero();
    }

    /** T2: an expired unpaid order releases the slot. */
    @Test
    void expiredPayment_releasesSlot() {
        Setup s = standardListing();
        OrderView o = order(user(), s, Duration.ofHours(5), 60);
        assertApi(() -> order(user(), s, Duration.ofHours(5), 60), "SLOT_UNAVAILABLE");
        advanceAndRun(Duration.ofMinutes(16));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.VOID);
        OrderView again = orders.create(user(), s.listingId(), o.startAt(), 60, UUID.randomUUID().toString()).order();
        assertThat(again.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** T3: a second order while the first deposit is still held needs its own deposit (R5). */
    @Test
    void secondOrder_needsNewDeposit_depositsSeparatedByOrder() {
        Setup a = standardListing();
        Setup b = listing(25_000, 150_000, true, EndMode.FORCE_AT_END);
        UUID renter = user();
        OrderView oa = paidOrder(renter, a, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(deposit(oa).status()).isEqualTo(DepositStatus.POST_RENTAL_HOLD);

        advanceAndRun(Duration.ofHours(5));
        OrderView ob = order(renter, b, Duration.ofHours(1), 120);
        assertThat(ob.amountToPay()).isEqualTo(50_000 + 150_000);
        assertThat(deposit(ob).id()).isNotEqualTo(deposit(oa).id());
        pay(ob);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.DEPOSIT_HELD, null, oa.id())).isEqualTo(200_000);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.DEPOSIT_HELD, null, ob.id())).isEqualTo(150_000);

        advanceAndRun(Duration.ofHours(72)); // A's hold ends; B unaffected
        assertThat(deposit(oa).status()).isEqualTo(DepositStatus.REFUNDED);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.DEPOSIT_HELD, null, ob.id())).isEqualTo(150_000);
        assertThat(totalLedger()).isZero();
    }

    /** T7: account not rotated → no new orders; booked order is cancelled OWNER_FAULT_NOT_READY with 100% refund. */
    @Test
    void accountNotRotated_blocksOrdersAndCancelsNext() {
        Setup s = standardListing();
        OrderView first = paidOrder(user(), s, Duration.ofHours(1), 60);
        UUID renter2 = user();
        OrderView next = paidOrder(renter2, s, Duration.ofHours(3), 60); // booked while AVAILABLE
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(first).status()).isEqualTo(OrderStatus.ENDED);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.ROTATION_PENDING);
        assertApi(() -> order(user(), s, Duration.ofHours(10), 60), "ACCOUNT_NOT_AVAILABLE");

        advanceAndRun(Duration.ofMinutes(61));
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.ROTATION_OVERDUE);

        advanceAndRun(Duration.ofMinutes(60));
        OrderView n = reload(next);
        assertThat(n.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(n.cancelReason()).isEqualTo(CancelReason.OWNER_FAULT_NOT_READY);
        assertThat(deposit(n).status()).isEqualTo(DepositStatus.REFUNDED);
        assertThat(payments.refundsForOrder(n.id())).extracting("amount").containsExactlyInAnyOrder(30_000L, 200_000L);
        assertThat(jdbc.queryForObject("select count(*) from abuse_signals where order_id = ? and type = 'OWNER_FAULT_NOT_READY'",
                Integer.class, n.id())).isEqualTo(1);

        // While the password is not changed, the listing is hidden from search.
        assertThat(listings.search(s.gameId(), null, null, null, null, 0, 20).content())
                .extracting(v -> v.id()).doesNotContain(s.listingId());

        // Rotation brings the account back, and the still-ACTIVE listing shows up in search again by itself.
        gameAccounts.rotate(s.ownerId(), s.accountId(), null, "new-pass-2", true);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.AVAILABLE);
        assertThat(listings.search(s.gameId(), null, null, null, null, 0, 20).content())
                .extracting(v -> v.id()).contains(s.listingId());
        assertThat(order(user(), s, Duration.ofHours(10), 60).status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(totalLedger()).isZero();
    }

    /** T9: manual approval, timeout → REJECTED + 100% refund; auto-accept forbidden for OTP games. */
    @Test
    void manualApproval_timeoutRejectsAndRefunds() {
        Setup s = listing(30_000, 200_000, false, EndMode.FORCE_AT_END);
        OrderView o = paidOrder(user(), s, Duration.ofHours(5), 60);
        assertThat(o.status()).isEqualTo(OrderStatus.AWAITING_OWNER_APPROVAL);
        advanceAndRun(Duration.ofMinutes(31));
        o = reload(o);
        assertThat(o.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(o.cancelReason()).isEqualTo(CancelReason.APPROVAL_TIMEOUT);
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.REFUNDED);

        OrderView o2 = paidOrder(user(), s, Duration.ofHours(8), 60);
        assertThat(orders.approve(s.ownerId(), o2.id()).status()).isEqualTo(OrderStatus.CONFIRMED);

        UUID otpGame = game(p -> new GamePolicy(p.name(), null, null, HandoverMode.OWNER_ASSISTED_OTP, true,
                p.depositMin(), p.depositMax(), 72, 72, 168, 15, 60, 1440, 10, 1000, 120, 5000, 12, 18, null, null));
        UUID owner = owner();
        UUID acc = gameAccounts.create(owner, otpGame, "VN", "otpuser", "pw-123456", true, true, true).id();
        assertApi(() -> listings.create(owner, acc, new ListingService.Content("t", "d", Map.of(), 30_000, 200_000,
                true, EndMode.FORCE_AT_END)), "AUTO_ACCEPT_NOT_ALLOWED");
        assertThat(totalLedger()).isZero();
    }

    /** T11: GRACE and STAY_ONLINE block scheduled_end + grace; STAY_ONLINE → AWAITING_RECLAIM → reclaim → rotation. */
    @Test
    void endModes() {
        Setup grace = listing(30_000, 200_000, true, EndMode.GRACE_FOR_CURRENT_MATCH);
        OrderView g = paidOrder(user(), grace, Duration.ofHours(1), 60);
        assertThat(Duration.between(g.scheduledEndAt(), g.blockEndAt())).isEqualTo(Duration.ofMinutes(10));
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(g).status()).isEqualTo(OrderStatus.IN_RENTAL); // still in grace
        advanceAndRun(Duration.ofMinutes(10));
        assertThat(reload(g).status()).isEqualTo(OrderStatus.ENDED);
        assertThat(gameAccounts.state(grace.accountId())).isEqualTo(SecurityState.ROTATION_PENDING);

        Setup stay = listing(30_000, 200_000, true, EndMode.STAY_ONLINE_UNTIL_RECLAIM);
        OrderView st = paidOrder(user(), stay, Duration.ofHours(1), 60);
        assertThat(Duration.between(st.scheduledEndAt(), st.blockEndAt())).isEqualTo(Duration.ofMinutes(10));
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(1));
        assertThat(reload(st).status()).isEqualTo(OrderStatus.IN_RENTAL); // renter still online during the buffer
        advanceAndRun(Duration.ofMinutes(10));
        assertThat(gameAccounts.state(stay.accountId())).isEqualTo(SecurityState.AWAITING_RECLAIM);
        assertApi(() -> order(user(), stay, Duration.ofHours(5), 60), "ACCOUNT_NOT_AVAILABLE");
        gameAccounts.reclaim(stay.ownerId(), stay.accountId());
        assertThat(gameAccounts.state(stay.accountId())).isEqualTo(SecurityState.ROTATION_PENDING);
        var v = gameAccounts.rotate(stay.ownerId(), stay.accountId(), null, "rotated-pass", true);
        assertThat(v.securityState()).isEqualTo(SecurityState.AVAILABLE);
        assertThat(v.activeCredentialVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from credential_versions where game_account_id = ? and status = 'RETIRED'",
                Integer.class, stay.accountId())).isEqualTo(1);
        assertThat(gameAccounts.decryptActive(stay.accountId()).password()).isEqualTo("rotated-pass");
    }

    /** The owner's own free-cancel window replaces the game's (120') for new orders; booked orders keep theirs. */
    @Test
    void ownerChosenFreeCancelWindow() {
        Setup s = standardListing();
        OrderView booked = paidOrder(user(), s, Duration.ofMinutes(60), 180);
        setFreeCancel(s, 30);
        assertThat(listings.get(s.listingId()).renterFreeCancelMinutes()).isEqualTo(30);

        // 60' before the start: late under the game's 120' (booked before the change), on time under the owner's 30'.
        orders.cancel(booked.renterId(), booked.id());
        assertThat(reload(booked).cancelReason()).isEqualTo(CancelReason.RENTER_LATE_CANCELLED);
        OrderView a = paidOrder(user(), s, Duration.ofMinutes(60), 180);
        orders.cancel(a.renterId(), a.id());
        assertThat(reload(a).cancelReason()).isEqualTo(CancelReason.RENTER_CANCELLED);

        // Stricter window: 5h before is now late.
        setFreeCancel(s, 360);
        OrderView b = paidOrder(user(), s, Duration.ofHours(5), 180);
        orders.cancel(b.renterId(), b.id());
        assertThat(reload(b).cancelReason()).isEqualTo(CancelReason.RENTER_LATE_CANCELLED);

        // Out of range is refused.
        assertApi(() -> setFreeCancel(s, ListingService.Content.MAX_FREE_CANCEL_MINUTES + 1), "VALIDATION_ERROR");
    }

    private void setFreeCancel(Setup s, int minutes) {
        var l = listings.get(s.listingId());
        listings.update(s.ownerId(), l.id(), l.version(), new ListingService.Content(l.title(), l.description(),
                l.attributes(), l.pricePerHour(), l.pricingTiers(), l.depositAmount(), l.riskTerms(), l.autoAccept(),
                l.endMode(), null, minutes));
    }

    /** T12: cancellation policy. */
    @Test
    void cancellations() {
        Setup s = standardListing();
        // Renter on time: 100% rent + deposit.
        OrderView a = paidOrder(user(), s, Duration.ofHours(5), 180);
        orders.cancel(a.renterId(), a.id());
        runJobs();
        assertThat(reload(a).cancelReason()).isEqualTo(CancelReason.RENTER_CANCELLED);
        assertThat(payments.refundsForOrder(a.id())).extracting("amount").containsExactlyInAnyOrder(90_000L, 200_000L);
        assertThat(deposit(a).status()).isEqualTo(DepositStatus.REFUNDED);

        // Renter late (60' before): 50% rent back, kept 45.000 → seller 40.500 + fee 4.500; deposit 100%.
        OrderView b = paidOrder(user(), s, Duration.ofMinutes(60), 180);
        orders.cancel(b.renterId(), b.id());
        assertThat(reload(b).cancelReason()).isEqualTo(CancelReason.RENTER_LATE_CANCELLED);
        assertThat(payments.refundsForOrder(b.id())).extracting("amount").containsExactlyInAnyOrder(45_000L, 200_000L);
        assertThat(ledger.liabilityBalanceForOrder(AccountCode.SELLER_REVIEW_HOLD, s.ownerId(), b.id())).isEqualTo(40_500);

        // Owner late cancel: 100% + OWNER_LATE_CANCEL signal.
        OrderView c = paidOrder(user(), s, Duration.ofHours(6), 60);
        orders.cancel(s.ownerId(), c.id());
        assertThat(reload(c).cancelReason()).isEqualTo(CancelReason.OWNER_CANCELLED);
        assertThat(jdbc.queryForObject("select count(*) from abuse_signals where order_id = ? and type = 'OWNER_LATE_CANCEL'",
                Integer.class, c.id())).isEqualTo(1);

        // In rental: no cancel.
        OrderView d = paidOrder(user(), s, Duration.ofHours(8), 60);
        advanceAndRun(Duration.ofHours(8));
        assertThat(reload(d).status()).isEqualTo(OrderStatus.IN_RENTAL);
        assertApi(() -> orders.cancel(d.renterId(), d.id()), "INVALID_STATE");

        // Unpaid: deposit VOID.
        OrderView e = order(user(), s, Duration.ofHours(20), 60);
        orders.cancel(e.renterId(), e.id());
        assertThat(deposit(e).status()).isEqualTo(DepositStatus.VOID);
        runJobs();
        assertThat(totalLedger()).isZero();
    }

    /** T16: game not ALLOWED → invisible, no orders, confirmed order cancelled at start. */
    @Test
    void failClosedOnGame() {
        Setup s = standardListing();
        OrderView o = paidOrder(user(), s, Duration.ofHours(2), 60);
        UUID admin = user("ADMIN");
        catalog.changeTosStatus(admin, s.gameId(), com.rentgg.catalog.domain.TosStatus.SUSPENDED, null, "notice", null);
        assertThat(listings.search(s.gameId(), null, null, null, null, 0, 20).totalElements()).isZero();
        assertApi(() -> order(user(), s, Duration.ofHours(5), 60), "GAME_NOT_ALLOWED");
        advanceAndRun(Duration.ofHours(2));
        assertThat(reload(o).cancelReason()).isEqualTo(CancelReason.PLATFORM_GAME_SUSPENDED);
        assertThat(jdbc.queryForObject("select count(*) from abuse_signals where order_id = ?", Integer.class, o.id())).isZero();
        assertApi(() -> catalog.changeTosStatus(admin, s.gameId(), com.rentgg.catalog.domain.TosStatus.ALLOWED, null, null, null),
                "APPROVAL_REQUIRED");
    }

    @Test
    void idempotentOrderCreation() {
        Setup s = standardListing();
        UUID renter = user();
        String key = UUID.randomUUID().toString();
        var start = now().plus(Duration.ofHours(4));
        var first = orders.create(renter, s.listingId(), start, 60, key);
        var replay = orders.create(renter, s.listingId(), start, 60, key);
        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.order().id()).isEqualTo(first.order().id());
        assertApi(() -> orders.create(renter, s.listingId(), start, 120, key), "IDEMPOTENCY_CONFLICT");
        assertApi(() -> orders.create(s.ownerId(), s.listingId(), start.plusSeconds(86400), 60, "x"), "CANNOT_RENT_OWN_LISTING");
        assertApi(() -> orders.create(renter, s.listingId(), start, 30, "y"), "DURATION_OUT_OF_RANGE");
        assertApi(() -> orders.create(renter, s.listingId(), now().minusSeconds(60), 60, "z"), "START_IN_PAST");
    }
}
