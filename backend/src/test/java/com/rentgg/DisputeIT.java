package com.rentgg;

import com.rentgg.approval.application.ApprovalService;
import com.rentgg.approval.domain.ApprovalRequest;
import com.rentgg.deposit.domain.DepositStatus;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeService.Decision;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.order.application.HandoverService;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import com.rentgg.wallet.application.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DisputeIT extends IntegrationTest {

    @Autowired DisputeService disputes;
    @Autowired WalletService wallet;
    @Autowired ApprovalService approvals;
    @Autowired HandoverService credentials;

    private OrderView endedOrder(Setup s, UUID renter) {
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        return o;
    }

    /** T13 + example 2 of §7.5: OWNER_DAMAGE, deduction 120.000 of 200.000. */
    @Test
    void ownerDamage_partialDeduction() {
        Setup s = standardListing();
        OrderView o = endedOrder(s, user());
        clock.advance(Duration.ofHours(20));
        var d = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_DAMAGE, "rank dropped",
                List.of("https://img.example/before.png", "https://img.example/after.png"));
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.DISPUTED);

        advanceAndRun(Duration.ofHours(52)); // T1 + 72h: revenue released, deposit NOT refunded
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(81_000);
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.DISPUTED);

        UUID support = user("SUPPORT");
        assertApi(() -> disputes.resolve(support, d.id(), new Decision(true, 200_001, 0, "DAMAGE", null, null)),
                "DEDUCTION_EXCEEDS_DEPOSIT");
        clock.advance(Duration.ofHours(18));
        disputes.resolve(support, d.id(), new Decision(true, 120_000, 0, "DAMAGE_CONFIRMED", "ok", null));
        runJobs();
        var dep = deposit(o);
        assertThat(dep.status()).isEqualTo(DepositStatus.SETTLED);
        assertThat(dep.deductedAmount()).isEqualTo(120_000);
        assertThat(dep.refundedAmount()).isEqualTo(80_000);
        // 81k revenue + 120k deduction − 10% platform risk fee (12k).
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(189_000);
        assertThat(payments.refundsForOrder(o.id())).extracting("amount").containsExactly(80_000L);
        assertThat(totalLedger()).isZero();
    }

    @Test
    void ownerDamage_afterHoldClosed_isRejected() {
        Setup s = standardListing();
        OrderView o = endedOrder(s, user());
        advanceAndRun(Duration.ofHours(73));
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.REFUNDED);
        assertApi(() -> disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_DAMAGE, "late", List.of()),
                "DISPUTE_WINDOW_CLOSED");
    }

    /** T14: takeover during the rental. */
    @Test
    void accountTakeover_duringRental() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        clock.advance(Duration.ofMinutes(30));
        var d = disputes.open(s.ownerId(), o.id(), DisputeType.ACCOUNT_TAKEOVER, "password changed",
                List.of("https://img.example/mail.png"));
        o = reload(o);
        assertThat(o.status()).isEqualTo(OrderStatus.ENDED);
        assertThat(o.actualEndAt()).isEqualTo(now());
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.DISPUTED);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.SUSPENDED_SECURITY_REVIEW);
        assertThat(users.get(renter).status()).isEqualTo(UserStatus.RESTRICTED);
        assertThat(wallet.balances(s.ownerId()).reviewHold()).isEqualTo(81_000); // revenue still recognised
        Setup other = standardListing();
        assertApi(() -> order(renter, other, Duration.ofHours(5), 60), "USER_RESTRICTED");
        OrderView ended = o;
        assertApi(() -> credentials.viewCredentials(renter, ended.id()), "CREDENTIAL_WINDOW_CLOSED");

        UUID support = user("SUPPORT");
        disputes.resolve(support, d.id(), new Decision(true, 200_000, 0, "TAKEOVER_CONFIRMED", null, UserStatus.SUSPENDED));
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.SETTLED);
        assertThat(users.get(renter).status()).isEqualTo(UserStatus.SUSPENDED);

        // Restore needs a new credential + dual approval.
        UUID admin1 = user("ADMIN");
        UUID admin2 = user("ADMIN");
        var a1 = approvals.create(support, ApprovalRequest.ActionType.ACCOUNT_SECURITY_RESTORE,
                Map.of("gameAccountId", s.accountId().toString()), "RECOVERED", null);
        assertApi(() -> approvals.approve(admin1, a1.getId()), "NEW_CREDENTIAL_REQUIRED");
        clock.advance(Duration.ofMinutes(5));
        gameAccounts.rotate(s.ownerId(), s.accountId(), null, "recovered-pass", true);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.SUSPENDED_SECURITY_REVIEW);
        var a2 = approvals.create(support, ApprovalRequest.ActionType.ACCOUNT_SECURITY_RESTORE,
                Map.of("gameAccountId", s.accountId().toString()), "RECOVERED", null);
        assertThat(approvals.approve(admin2, a2.getId()).getStatus()).isEqualTo(ApprovalRequest.Status.EXECUTED);
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.AVAILABLE);
        assertThat(totalLedger()).isZero();
    }

    /** T15: renter dispute moves revenue to dispute hold; hold job skips; resolution refunds rent proportionally. */
    @Test
    void renterLoginFailed_disputeHoldAndRefund() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_LOGIN_FAILED, "x", List.of()),
                "COMPLAINT_WINDOW_CLOSED"); // credentials not viewed yet
        credentials.viewCredentials(renter, o.id());
        clock.advance(Duration.ofMinutes(5));
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_LOGIN_FAILED, "wrong password",
                List.of("https://img.example/err.png"));
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_LOGIN_FAILED, "again", List.of()),
                "DISPUTE_ALREADY_OPEN");

        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        var b = wallet.balances(s.ownerId());
        assertThat(b.reviewHold()).isZero();
        assertThat(b.disputeHold()).isEqualTo(81_000);

        advanceAndRun(Duration.ofHours(80)); // past seller_hold_until but dispute open → not released
        assertThat(wallet.balances(s.ownerId()).available()).isZero();

        disputes.resolve(user("SUPPORT"), d.id(), new Decision(true, 0, 90_000, "LOGIN_FAILED_CONFIRMED", null, null));
        runJobs();
        b = wallet.balances(s.ownerId());
        assertThat(b.disputeHold()).isZero();
        assertThat(b.available()).isZero();
        assertThat(ledger.liabilityBalance(AccountCode.PLATFORM_REVENUE, null)).isGreaterThanOrEqualTo(0);
        assertThat(payments.refundsForOrder(o.id())).extracting("amount").contains(90_000L, 200_000L);
        assertThat(jdbc.queryForObject("select count(*) from abuse_signals where order_id = ? and type = 'OWNER_LOGIN_FAILED_CONFIRMED'",
                Integer.class, o.id())).isEqualTo(1);
        assertThat(totalLedger()).isZero();
    }

    @Test
    void highRiskSeller_hold168h() {
        Setup s = standardListing();
        users.setRiskTier(user("ADMIN"), s.ownerId(), com.rentgg.identity.domain.RiskTier.HIGH, "TEST");
        OrderView o = endedOrder(s, user());
        assertThat(Duration.between(reload(o).actualEndAt(), reload(o).sellerHoldUntil())).isEqualTo(Duration.ofHours(168));
        advanceAndRun(Duration.ofHours(100));
        assertThat(wallet.balances(s.ownerId()).available()).isZero();
        advanceAndRun(Duration.ofHours(70));
        assertThat(wallet.balances(s.ownerId()).available()).isEqualTo(81_000);
    }

    @Test
    void supportFindsDisputeByOrderIdOrEitherPartysEmail() {
        Setup s = standardListing();
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        var d = disputes.open(s.ownerId(), o.id(), DisputeType.ACCOUNT_TAKEOVER, "password changed", List.of());
        var page = org.springframework.data.domain.PageRequest.of(0, 50);

        for (String q : List.of(o.id().toString().substring(0, 8), d.id().toString(), users.get(renter).email(),
                users.get(s.ownerId()).email().toUpperCase(), renter.toString().substring(0, 8))) {
            assertThat(disputes.search(q, null, null, page).getContent()).as(q)
                    .extracting(x -> x.id()).contains(d.id());
        }
        assertThat(disputes.search(users.get(renter).email(), null, DisputeType.OWNER_DAMAGE, page).getContent())
                .isEmpty();
        assertThat(disputes.search("%", null, null, page).getContent()).extracting(x -> x.id()).doesNotContain(d.id());
    }
}
