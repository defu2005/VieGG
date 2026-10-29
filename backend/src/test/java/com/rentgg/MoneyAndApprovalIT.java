package com.rentgg;

import com.rentgg.approval.application.ApprovalService;
import com.rentgg.approval.domain.ApprovalRequest;
import com.rentgg.catalog.domain.TosStatus;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.shared.error.ApiException;
import com.rentgg.support.IntegrationTest;
import com.rentgg.wallet.application.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ledger, wallet, approvals and concurrency (T1, T4, T5, T6, T16, T17, T18). */
class MoneyAndApprovalIT extends IntegrationTest {

    @Autowired WalletService wallet;
    @Autowired ApprovalService approvals;
    @Autowired com.rentgg.notification.application.NotificationService notifications;

    private Setup sellerWithAvailable81k() {
        Setup s = standardListing();
        paidOrder(user(), s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        advanceAndRun(Duration.ofHours(72));
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(81_000);
        return s;
    }

    /** T1: 10 concurrent bookings of overlapping slots → exactly one wins. Adjacent [) slots do not conflict. */
    @Test
    void concurrentBooking_exactlyOneWins() throws Exception {
        Setup s = standardListing();
        var start = now().plus(Duration.ofHours(5));
        List<UUID> renters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            renters.add(user());
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID r = renters.get(i);
            int minutes = 60 + i * 10;
            results.add(pool.submit(() -> {
                go.await();
                try {
                    orders.create(r, s.listingId(), start, minutes, UUID.randomUUID().toString());
                    return "OK";
                } catch (ApiException e) {
                    return e.code();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("SLOT_UNAVAILABLE"::equals).hasSize(9);
        Integer winners = jdbc.queryForObject("select count(*) from orders where listing_id = ? and status = 'PENDING_PAYMENT'",
                Integer.class, s.listingId());
        assertThat(winners).isEqualTo(1);
        OrderView w = orders.list(renters.stream().filter(r -> !orders.list(r, "RENTER", null,
                org.springframework.data.domain.PageRequest.of(0, 1)).isEmpty()).findFirst().orElseThrow(),
                "RENTER", null, org.springframework.data.domain.PageRequest.of(0, 1)).getContent().get(0);
        // Adjacent slot starting exactly at block_end_at is accepted.
        OrderView adjacent = orders.create(user(), s.listingId(), w.blockEndAt(), 60, UUID.randomUUID().toString()).order();
        assertThat(adjacent.status()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** T5: available 81.000, 5 parallel withdrawals of 50.000 → one succeeds; same key replays. */
    @Test
    void withdrawals_neverExceedAvailable() throws Exception {
        Setup s = sellerWithAvailable81k();
        var bank = new WalletService.BankAccount("VCB", "0123456789", "Owner");
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(pool.submit(() -> {
                go.await();
                try {
                    wallet.requestWithdrawal(s.ownerId(), 50_000, bank, UUID.randomUUID().toString());
                    return "OK";
                } catch (ApiException e) {
                    return e.code();
                }
            }));
        }
        go.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("INSUFFICIENT_AVAILABLE_BALANCE"::equals).hasSize(4);
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(31_000);

        String key = UUID.randomUUID().toString();
        var first = wallet.requestWithdrawal(s.ownerId(), 31_000, bank, key);
        var replay = wallet.requestWithdrawal(s.ownerId(), 31_000, bank, key);
        assertThat(replay.created()).isFalse();
        assertThat(replay.withdrawal().getId()).isEqualTo(first.withdrawal().getId());
        assertThat(wallet.balances(s.ownerId()).available()).isZero();

        wallet.markFailed(user("FINANCE"), first.withdrawal().getId(), "wrong account");
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(31_000);
        assertThat(totalLedger()).isZero();
    }

    /** T4: unbalanced postings fail at COMMIT; ledger rows cannot be updated or deleted. */
    @Test
    void ledgerIsBalancedAndAppendOnly() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jdbc.update("""
                with t as (insert into ledger_transactions (id, type, idempotency_key) values (gen_random_uuid(), 'LEDGER_ADJUSTMENT', ?) returning id)
                insert into ledger_postings (id, transaction_id, account_id, amount)
                select gen_random_uuid(), t.id, '00000000-0000-0000-0000-000000000001', 100 from t""", "bad-" + UUID.randomUUID())))
                .hasMessageContaining("unbalanced");
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledger.post(TxType.LEDGER_ADJUSTMENT, null, null, "x-" + UUID.randomUUID(), null,
                List.of(Posting.debit(AccountCode.PSP_CLEARING, 10), Posting.credit(AccountCode.RENT_ESCROW, 9)))))
                .isInstanceOf(IllegalStateException.class);
        sellerWithAvailable81k();
        assertThatThrownBy(() -> jdbc.update("update ledger_postings set amount = amount + 1")).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from ledger_transactions")).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_logs")).rootCause().hasMessageContaining("append-only");
        assertThat(totalLedger()).isZero();
    }

    /** T6: no self-approval (service and DB), expired approvals cannot be approved, role rules apply. */
    @Test
    void dualApproval() {
        Setup s = sellerWithAvailable81k();
        UUID f1 = user("FINANCE");
        UUID f2 = user("FINANCE");
        var adj = approvals.create(f1, ApprovalRequest.ActionType.LEDGER_ADJUSTMENT, Map.of(
                "postings", List.of(
                        Map.of("accountCode", "PLATFORM_REVENUE", "amount", 1_000),
                        Map.of("accountCode", "SELLER_AVAILABLE", "ownerUserId", s.ownerId().toString(), "amount", -1_000))),
                "GOODWILL", null);
        assertApi(() -> approvals.approve(f1, adj.getId()), "SELF_APPROVAL_FORBIDDEN");
        assertApi(() -> approvals.approve(user("SUPPORT"), adj.getId()), "FORBIDDEN");
        assertThatThrownBy(() -> jdbc.update("update approval_requests set approved_by = requested_by where id = ?", adj.getId()))
                .hasMessageContaining("ck_approval_four_eyes");
        assertThat(approvals.approve(f2, adj.getId()).getStatus()).isEqualTo(ApprovalRequest.Status.EXECUTED);
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(82_000);

        var stale = approvals.create(f1, ApprovalRequest.ActionType.LEDGER_ADJUSTMENT, Map.of(
                "postings", List.of(
                        Map.of("accountCode", "PLATFORM_REVENUE", "amount", 1),
                        Map.of("accountCode", "PSP_CLEARING", "amount", -1))), "TEST", null);
        clock.advance(Duration.ofHours(73));
        assertApi(() -> approvals.approve(f2, stale.getId()), "APPROVAL_EXPIRED");
        assertApi(() -> approvals.create(user(), ApprovalRequest.ActionType.GAME_ALLOW,
                Map.of("gameId", s.gameId().toString()), "X", null), "FORBIDDEN");
        assertThat(totalLedger()).isZero();
    }

    /** T16 (second half): re-allowing a SUSPENDED game goes through GAME_ALLOW dual approval. */
    @Test
    void gameAllowNeedsApproval() {
        UUID gameId = game();
        UUID a1 = user("ADMIN");
        UUID a2 = user("ADMIN");
        catalog.changeTosStatus(a1, gameId, TosStatus.SUSPENDED, null, null, null);
        var req = approvals.create(a1, ApprovalRequest.ActionType.GAME_ALLOW,
                Map.of("gameId", gameId.toString()), "TOS_REVIEW", null);
        assertThat(catalog.get(gameId).tosStatus()).isEqualTo(TosStatus.SUSPENDED);
        approvals.approve(a2, req.getId());
        assertThat(catalog.get(gameId).tosStatus()).isEqualTo(TosStatus.ALLOWED);
    }

    /** T17: all three recovery attestations required; unverified account cannot be submitted. */
    @Test
    void recoveryAttestation() {
        UUID gameId = game();
        UUID owner = owner();
        assertApi(() -> gameAccounts.create(owner, gameId, "VN", "u", "p", true, false, true), "RECOVERY_ATTESTATION_REQUIRED");
        UUID acc = gameAccounts.create(owner, gameId, "VN", "username1", "pw-1234567", true, true, true).id();
        // The same login (case/whitespace ignored, any password) cannot be added again, by this owner or another one.
        assertApi(() -> gameAccounts.create(owner, gameId, "VN", " UserName1 ", "other-pass", true, true, true),
                "GAME_ACCOUNT_DUPLICATE");
        assertApi(() -> gameAccounts.create(owner(), gameId, "VN", "username1", "pw-1234567", true, true, true),
                "GAME_ACCOUNT_DUPLICATE");
        // The same username in another game is a different account.
        gameAccounts.create(owner, game(), "VN", "username1", "pw-1234567", true, true, true);
        assertApi(() -> listings.create(owner, acc, new com.rentgg.listing.application.ListingService.Content("t", "d",
                Map.of(), 30_000, 10, false, com.rentgg.listing.domain.EndMode.FORCE_AT_END)), "DEPOSIT_OUT_OF_RANGE");
        var l = listings.create(owner, acc, new com.rentgg.listing.application.ListingService.Content("t", "d", Map.of(),
                30_000, 200_000, false, com.rentgg.listing.domain.EndMode.FORCE_AT_END));
        assertApi(() -> listings.submit(owner, l.id()), "RECOVERY_NOT_VERIFIED");
        // One listing per account: a second create for the same account is refused, even while the first is a draft.
        assertApi(() -> listings.create(owner, acc, new com.rentgg.listing.application.ListingService.Content("t2", "d",
                Map.of(), 30_000, 200_000, false, com.rentgg.listing.domain.EndMode.FORCE_AT_END)), "LISTING_ALREADY_EXISTS");
        // The new account is in the admin verification queue, and admins were notified.
        assertThat(gameAccounts.listByState(com.rentgg.gameaccount.domain.SecurityState.PENDING_VERIFICATION))
                .extracting(v -> v.id()).contains(acc);
        UUID admin = user("ADMIN");
        jobs.publishOutbox();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'GameAccountPendingVerification' and data->>'gameAccountId' = ?",
                Integer.class, admin, acc.toString())).isEqualTo(1);
        gameAccounts.verifyRecovery(admin, acc);
        assertThat(gameAccounts.listByState(com.rentgg.gameaccount.domain.SecurityState.PENDING_VERIFICATION))
                .extracting(v -> v.id()).doesNotContain(acc);
        assertThat(listings.submit(owner, l.id()).status().name()).isEqualTo("PENDING_REVIEW");
        jobs.publishOutbox();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ? and type = 'ListingSubmittedForReview' and data->>'listingId' = ?",
                Integer.class, admin, l.id().toString())).isEqualTo(1);
        // Unread count and "mark all read" for the nav badge.
        assertThat(notifications.unreadCount(admin)).isGreaterThanOrEqualTo(2);
        assertThat(notifications.markAllRead(admin)).isGreaterThanOrEqualTo(2);
        assertThat(notifications.unreadCount(admin)).isZero();
    }

    /**
     * An old duplicate account (same login as another one, kept without a fingerprint) can still change its password;
     * only a changed login is checked for duplicates.
     */
    @Test
    void rotation_onlyChecksDuplicatesWhenLoginChanges() {
        UUID gameId = game();
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID legacy = gameAccounts.create(owner, gameId, "VN", "shared_login", "pw-1", true, true, true).id();
        jdbc.update("update game_accounts set login_fingerprint = null where id = ?", legacy);
        UUID newer = gameAccounts.create(owner, gameId, "VN", "shared_login", "pw-2", true, true, true).id();
        UUID third = gameAccounts.create(owner, gameId, "VN", "third_login", "pw-3", true, true, true).id();
        gameAccounts.verifyRecovery(admin, legacy);
        gameAccounts.verifyRecovery(admin, newer);
        gameAccounts.verifyRecovery(admin, third);

        // Password only, or the same login re-typed with other case/spaces: allowed, fingerprint stays empty.
        assertThat(gameAccounts.rotate(owner, legacy, null, "new-pw-1", true).activeCredentialVersion()).isEqualTo(2);
        assertThat(gameAccounts.rotate(owner, legacy, " Shared_Login ", "new-pw-2", true).activeCredentialVersion()).isEqualTo(3);
        assertThat(jdbc.queryForObject("select login_fingerprint from game_accounts where id = ?", String.class, legacy)).isNull();
        // Changing to a login another account already uses is still refused.
        assertApi(() -> gameAccounts.rotate(owner, legacy, "third_login", "new-pw-3", true), "GAME_ACCOUNT_DUPLICATE");
        assertApi(() -> gameAccounts.rotate(owner, newer, "third_login", "new-pw-3", true), "GAME_ACCOUNT_DUPLICATE");
        // Changing to a free login gives the old account its fingerprint.
        gameAccounts.rotate(owner, legacy, "fresh_login", "new-pw-4", true);
        assertThat(jdbc.queryForObject("select login_fingerprint from game_accounts where id = ?", String.class, legacy)).isNotNull();
        assertThat(gameAccounts.decryptActive(legacy).username()).isEqualTo("fresh_login");
    }

    /** T18: running jobs twice / in parallel never double-posts or double-transitions. */
    @Test
    void jobsAreIdempotent() throws Exception {
        Setup s = standardListing();
        OrderView o = paidOrder(user(), s, Duration.ofHours(1), 180);
        clock.advance(Duration.ofHours(1));
        runParallel(() -> jobs.startDueOrders());
        clock.advance(Duration.ofHours(3));
        runParallel(() -> jobs.endDueOrders());
        clock.advance(Duration.ofHours(72));
        runParallel(() -> jobs.releaseSellerHolds());
        runParallel(() -> jobs.releaseDeposits());
        runParallel(() -> jobs.publishOutbox());
        runParallel(() -> jobs.publishOutbox());
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where order_id = ? and type = 'ORDER_ENDED'",
                Integer.class, o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transactions where order_id = ? and type = 'SELLER_HOLD_RELEASED'",
                Integer.class, o.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from refunds where order_id = ?", Integer.class, o.id())).isEqualTo(1);
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(81_000);
        assertThat(totalLedger()).isZero();
        assertThat(ledger.reconcile().get("depositHeldMismatches")).asList().isEmpty();
    }

    private void runParallel(Runnable r) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> a = pool.submit(r);
        Future<?> b = pool.submit(r);
        a.get(60, TimeUnit.SECONDS);
        b.get(60, TimeUnit.SECONDS);
        r.run();
        pool.shutdown();
    }
}
