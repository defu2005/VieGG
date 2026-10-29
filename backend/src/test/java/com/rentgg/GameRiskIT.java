package com.rentgg;

import com.rentgg.catalog.application.CatalogService.RiskInput;
import com.rentgg.catalog.application.GameRiskView;
import com.rentgg.catalog.application.GameView;
import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.catalog.domain.SuggestedPackage;
import com.rentgg.deposit.domain.DepositStatus;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeService.Decision;
import com.rentgg.dispute.application.DisputeView;
import com.rentgg.dispute.domain.DisputeItem;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.RiskTerm;
import com.rentgg.order.application.HandoverService;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-game risks: owner deposits per OWNER risk, compensation per RENTER risk, complaint windows. */
class GameRiskIT extends IntegrationTest {

    @Autowired DisputeService disputes;
    @Autowired HandoverService credentials;

    record RiskGame(UUID gameId, GameRiskView items, GameRiskView email, GameRiskView ban, GameRiskView wrongPass,
                    GameRiskView notAsDescribed) {}

    private RiskGame riskGame() {
        UUID gameId = game();
        GameView g = catalog.replaceRisks(user("ADMIN"), gameId, List.of(
                new RiskInput(null, RiskSide.OWNER, "Mất / bán vật phẩm", "Renter bán đồ", "Bằng giá trị vật phẩm đắt nhất", null),
                new RiskInput(null, RiskSide.OWNER, "Đổi email khôi phục", null, "  ", 60),
                new RiskInput(null, RiskSide.OWNER, "Account bị khóa", null, "Bằng giá trị account", 10, false),
                new RiskInput(null, RiskSide.RENTER, "Sai tài khoản / mật khẩu", null, "50.000 đ", 15),
                new RiskInput(null, RiskSide.RENTER, "Account không đúng mô tả", null, null, 15)));
        assertThat(g.risks()).extracting(GameRiskView::recommendation).containsExactly("Bằng giá trị vật phẩm đắt nhất",
                null, "Bằng giá trị account", "50.000 đ", null); // blank → null
        assertThat(g.risks()).extracting(GameRiskView::name).containsExactly("Mất / bán vật phẩm",
                "Đổi email khôi phục", "Account bị khóa", "Sai tài khoản / mật khẩu", "Account không đúng mô tả");
        var r = g.risks();
        return new RiskGame(gameId, r.get(0), r.get(1), r.get(2), r.get(3), r.get(4));
    }

    private ListingService.Content content(List<RiskTerm> terms) {
        return new ListingService.Content("Acc", "desc", Map.of(), 30_000, List.of(), 0, terms, true,
                EndMode.FORCE_AT_END);
    }

    private Setup riskListing(RiskGame g) {
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID account = gameAccounts.create(owner, g.gameId(), "VN", "player_" + owner.toString().substring(0, 6),
                "secret-pass-1", true, true, true).id();
        gameAccounts.verifyRecovery(admin, account);

        // The owner may skip owner risks, but at least one deposit is needed (orders always carry a deposit).
        assertApi(() -> listings.create(owner, account, content(List.of(new RiskTerm(g.wrongPass().id(), 50_000L)))),
                "RISK_DEPOSIT_REQUIRED");
        // The sum must stay in the game's deposit range.
        assertApi(() -> listings.create(owner, account, content(List.of(new RiskTerm(g.items().id(), 2_000_000L),
                new RiskTerm(g.email().id(), 50_000L), new RiskTerm(g.ban().id(), 30_000L)))), "DEPOSIT_OUT_OF_RANGE");
        assertApi(() -> listings.create(owner, account, content(List.of(new RiskTerm(UUID.randomUUID(), 1L)))),
                "UNKNOWN_RISK");

        // Deposit = max(stackable 120k + 50k, exclusive ban 300k) = 300k.
        var l = listings.create(owner, account, content(List.of(new RiskTerm(g.items().id(), 120_000L),
                new RiskTerm(g.email().id(), 50_000L), new RiskTerm(g.ban().id(), 300_000L),
                new RiskTerm(g.wrongPass().id(), 50_000L))));
        assertThat(l.depositAmount()).isEqualTo(300_000);
        assertThat(g.ban().stackable()).isFalse();
        assertThat(l.riskAmount(g.wrongPass().id())).isEqualTo(50_000L);
        assertThat(l.riskAmount(g.notAsDescribed().id())).isNull(); // refund only
        listings.submit(owner, l.id());
        listings.approve(admin, l.id());
        return new Setup(g.gameId(), owner, account, l.id());
    }

    @Test
    void renterRisk_windowAndCompensation() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        assertThat(o.depositAmount()).isEqualTo(300_000);
        assertThat((List<?>) o.policySnapshot().get("risks")).hasSize(5);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());

        // Wrong side / owner cannot open a renter risk.
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.items().id(), "x", List.of()),
                "RISK_SIDE_MISMATCH");
        assertApi(() -> disputes.open(s.ownerId(), o.id(), DisputeType.RENTER_RISK, g.wrongPass().id(), "x", List.of()),
                "NOT_RESOURCE_OWNER");

        clock.advance(Duration.ofMinutes(5));
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.wrongPass().id(), "wrong password", List.of());
        assertThat(d.riskId()).isEqualTo(g.wrongPass().id());
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.wrongPass().id(), "again", List.of()),
                "DISPUTE_ALREADY_OPEN");

        // 15-minute window from the first credential view.
        clock.advance(Duration.ofMinutes(11));
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.notAsDescribed().id(), "late", List.of()),
                "COMPLAINT_WINDOW_CLOSED");

        UUID support = user("SUPPORT");
        assertThat(d.items()).hasSize(1);
        UUID item = d.items().get(0).id();
        // Renter complaints are decided per risk; the money is computed, not typed in.
        assertApi(() -> disputes.resolve(support, d.id(), new Decision(true, 0, 30_000, 50_000, "LOGIN_FAILED", null, null)),
                "RESOLVE_PER_RISK");
        // The owner's wallet is empty while the order is still running.
        assertApi(() -> disputes.decideItem(support, d.id(), item, true, "EVIDENCE_CONFIRMED", null),
                "OWNER_BALANCE_INSUFFICIENT");
        topup(s.ownerId(), 60_000);
        long renterBefore = walletBalance(renter);
        var resolved = disputes.decideItem(support, d.id(), item, true, "EVIDENCE_CONFIRMED", null);
        assertThat(resolved.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.compensation()).isEqualTo(50_000);
        assertThat(resolved.rentRefund()).isEqualTo(o.rentAmount()); // whole rent still in escrow
        assertThat(walletBalance(s.ownerId())).isEqualTo(10_000); // the owner pays the full 50k
        // The platform keeps 10% of the compensation: the renter's wallet gets 45k (the rent refund goes to the PSP).
        assertThat(walletBalance(renter) - renterBefore).isEqualTo(45_000);
        assertThat(totalLedger()).isZero();
    }

    @Test
    void renterRisk_withoutCompensation_refundOnly() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.notAsDescribed().id(), "not as described", List.of());
        UUID support = user("SUPPORT");
        var resolved = disputes.decideItem(support, d.id(), d.items().get(0).id(), true, "EVIDENCE_CONFIRMED", null);
        assertThat(resolved.compensation()).isZero();
        assertThat(resolved.rentRefund()).isEqualTo(o.rentAmount());
        assertThat(totalLedger()).isZero();
    }

    /** One complaint lists several risks; support decides each, the last decision computes and pays. */
    @Test
    void renterRisks_severalInOneComplaint_decidedOneByOne() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_RISK, List.of(g.wrongPass().id(), g.items().id()),
                "x", List.of()), "RISK_SIDE_MISMATCH");
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_RISK,
                List.of(g.wrongPass().id(), g.notAsDescribed().id()), "both", List.of());
        assertThat(d.riskId()).isNull();
        assertThat(d.items()).extracting(DisputeView.Item::riskId)
                .containsExactly(g.wrongPass().id(), g.notAsDescribed().id());
        assertApi(() -> disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.notAsDescribed().id(), "again", List.of()),
                "DISPUTE_ALREADY_OPEN");

        UUID support = user("SUPPORT");
        topup(s.ownerId(), 50_000);
        long renterBefore = walletBalance(renter);
        var afterFirst = disputes.decideItem(support, d.id(), d.items().get(0).id(), true, "EVIDENCE_CONFIRMED", "ok");
        assertThat(afterFirst.status()).isEqualTo(DisputeStatus.OPEN); // nothing paid until every risk is decided
        assertThat(afterFirst.items().get(0).status()).isEqualTo(DisputeItem.Status.ACCEPTED);
        assertThat(afterFirst.items().get(0).compensation()).isEqualTo(50_000);
        assertThat(walletBalance(s.ownerId())).isEqualTo(50_000);
        // Deciding the same risk again changes nothing.
        disputes.decideItem(support, d.id(), d.items().get(0).id(), false, "INSUFFICIENT_EVIDENCE", null);

        var done = disputes.decideItem(support, d.id(), d.items().get(1).id(), false, "INSUFFICIENT_EVIDENCE", null);
        assertThat(done.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(done.reasonCode()).isEqualTo("RISKS_CONFIRMED");
        assertThat(done.compensation()).isEqualTo(50_000);
        assertThat(done.rentRefund()).isEqualTo(o.rentAmount());
        assertThat(walletBalance(s.ownerId())).isZero();
        assertThat(walletBalance(renter) - renterBefore).isEqualTo(45_000); // 50k − 10% platform fee
        assertThat(totalLedger()).isZero();
    }

    @Test
    void renterRisks_allRejected_nothingPaid() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_RISK,
                List.of(g.wrongPass().id(), g.notAsDescribed().id()), "both", List.of());
        UUID support = user("SUPPORT");
        disputes.decideItem(support, d.id(), d.items().get(0).id(), false, "INSUFFICIENT_EVIDENCE", null);
        var done = disputes.decideItem(support, d.id(), d.items().get(1).id(), false, "FALSE_CLAIM", null);
        assertThat(done.status()).isEqualTo(DisputeStatus.REJECTED);
        assertThat(done.rentRefund()).isZero();
        assertThat(done.compensation()).isZero();
        assertThat(totalLedger()).isZero();
    }

    /** Rental ended while the complaint was open: the refund comes from the owner's revenue held for it. */
    @Test
    void renterRisk_decidedAfterOrderEnded_refundsFromHeldRevenue() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_RISK, g.notAsDescribed().id(), "x", List.of());
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        var done = disputes.decideItem(user("SUPPORT"), d.id(), d.items().get(0).id(), true, "EVIDENCE_CONFIRMED", null);
        assertThat(done.status()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(done.rentRefund()).isEqualTo(o.rentAmount());
        assertThat(totalLedger()).isZero();
    }

    @Test
    void ownerRisks_cappedPerRisk_andSettledTogether() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        assertApi(() -> disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_RISK, g.items().id(), "early", List.of()),
                "INVALID_STATE");
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);

        clock.advance(Duration.ofMinutes(30));
        var items = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_RISK, g.items().id(), "items sold", List.of());
        var email = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_RISK, g.email().id(), "email changed", List.of());
        // "Account bị khóa" only has a 10-minute window after the rental.
        assertApi(() -> disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_RISK, g.ban().id(), "banned", List.of()),
                "COMPLAINT_WINDOW_CLOSED");
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.DISPUTED);

        UUID support = user("SUPPORT");
        assertApi(() -> disputes.resolve(support, items.id(), new Decision(true, 120_001, 0, "DAMAGE", null, null)),
                "DEDUCTION_EXCEEDS_RISK_DEPOSIT");
        disputes.resolve(support, items.id(), new Decision(true, 120_000, 0, "DAMAGE", null, null));
        assertThat(deposit(o).status()).isEqualTo(DepositStatus.DISPUTED); // waits for the other complaint
        long ownerBefore = walletBalance(s.ownerId());
        disputes.resolve(support, email.id(), new Decision(true, 50_000, 0, "DAMAGE", null, null));
        var dep = deposit(o);
        assertThat(dep.status()).isEqualTo(DepositStatus.SETTLED);
        assertThat(dep.deductedAmount()).isEqualTo(170_000);
        assertThat(dep.refundedAmount()).isEqualTo(130_000);
        // The renter loses 170k; the platform keeps 10%, the owner gets 153k.
        assertThat(walletBalance(s.ownerId()) - ownerBefore).isEqualTo(153_000);
        assertThat(totalLedger()).isZero();
    }

    /** A confirmed exclusive risk (permanent ban) replaces the stackable deductions: the renter pays only it. */
    @Test
    void exclusiveRisk_renterPaysOnlyThatDeposit() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        OrderView o = paidOrder(user(), s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);

        var items = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_RISK, g.items().id(), "items sold", List.of());
        var ban = disputes.open(s.ownerId(), o.id(), DisputeType.OWNER_RISK, g.ban().id(), "banned", List.of());
        UUID support = user("SUPPORT");
        disputes.resolve(support, items.id(), new Decision(true, 120_000, 0, "DAMAGE", null, null));
        assertApi(() -> disputes.resolve(support, ban.id(), new Decision(true, 300_001, 0, "BANNED", null, null)),
                "DEDUCTION_EXCEEDS_RISK_DEPOSIT");
        disputes.resolve(support, ban.id(), new Decision(true, 250_000, 0, "BANNED", null, null));
        var dep = deposit(o);
        assertThat(dep.status()).isEqualTo(DepositStatus.SETTLED);
        assertThat(dep.deductedAmount()).isEqualTo(250_000); // not 120k + 250k
        assertThat(dep.refundedAmount()).isEqualTo(50_000);
        assertThat(totalLedger()).isZero();
    }

    /** The owner opts out of an owner risk: no deposit share for it and no deposit claim for it later. */
    @Test
    void ownerRisk_optedOut_cannotBeClaimed() {
        RiskGame g = riskGame();
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID account = gameAccounts.create(owner, g.gameId(), "VN", "player_" + owner.toString().substring(0, 6),
                "secret-pass-1", true, true, true).id();
        gameAccounts.verifyRecovery(admin, account);
        // Only "items" (stackable) and "ban" (exclusive); "email" is left out.
        var l = listings.create(owner, account, content(List.of(new RiskTerm(g.items().id(), 120_000L),
                new RiskTerm(g.ban().id(), 200_000L))));
        assertThat(l.depositAmount()).isEqualTo(200_000);
        assertThat(l.riskAmount(g.email().id())).isNull();
        listings.submit(owner, l.id());
        listings.approve(admin, l.id());
        Setup s = new Setup(g.gameId(), owner, account, l.id());

        OrderView o = paidOrder(user(), s, Duration.ofHours(1), 180);
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(reload(o).status()).isEqualTo(OrderStatus.ENDED);
        assertApi(() -> disputes.open(owner, o.id(), DisputeType.OWNER_RISK, g.email().id(), "email changed", List.of()),
                "RISK_NOT_COVERED");
        var items = disputes.open(owner, o.id(), DisputeType.OWNER_RISK, g.items().id(), "items sold", List.of());
        assertThat(items.riskId()).isEqualTo(g.items().id());
    }

    @Test
    void suggestedPackages_replacedByStaff() {
        UUID gameId = game();
        UUID admin = user("ADMIN");
        GameView g = catalog.replaceSuggestedPackages(admin, gameId, List.of(
                new SuggestedPackage(180, 80_000L), new SuggestedPackage(60, null), new SuggestedPackage(120, 0L)));
        assertThat(g.suggestedPackages()).containsExactly(new SuggestedPackage(60, null),
                new SuggestedPackage(120, null), new SuggestedPackage(180, 80_000L));
        assertThat(catalog.get(gameId).suggestedPackages()).hasSize(3);
        assertApi(() -> catalog.replaceSuggestedPackages(admin, gameId, List.of(new SuggestedPackage(60, 1L),
                new SuggestedPackage(60, 2L))), "DUPLICATE_PRICING_TIER");
        assertApi(() -> catalog.replaceSuggestedPackages(admin, gameId, List.of(new SuggestedPackage(1, 1L))),
                "DURATION_OUT_OF_RANGE");
        assertThat(catalog.replaceSuggestedPackages(admin, gameId, List.of()).suggestedPackages()).isEmpty();
    }

    @Test
    void archivedRisk_keepsOrderSnapshot() {
        RiskGame g = riskGame();
        Setup s = riskListing(g);
        OrderView o = paidOrder(user(), s, Duration.ofHours(1), 60);
        GameView after = catalog.replaceRisks(user("ADMIN"), g.gameId(), List.of(
                new RiskInput(g.items().id(), RiskSide.OWNER, "Mất / bán vật phẩm", null, "Khoảng 150.000 đ", null)));
        assertThat(after.risks()).hasSize(1);
        assertThat(after.risks().get(0).recommendation()).isEqualTo("Khoảng 150.000 đ");
        assertThat((List<?>) reload(o).policySnapshot().get("risks")).hasSize(5);
        // The listing keeps its deposit until the owner edits it.
        assertThat(listings.get(s.listingId()).depositAmount()).isEqualTo(300_000);
    }
}
