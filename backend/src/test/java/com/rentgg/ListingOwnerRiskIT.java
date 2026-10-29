package com.rentgg;

import com.rentgg.catalog.application.CatalogService.RiskInput;
import com.rentgg.catalog.application.GameRiskView;
import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.application.ListingService.OwnerRisk;
import com.rentgg.listing.application.ListingView;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.order.application.HandoverService;
import com.rentgg.order.application.OrderView;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Owners add their own risks (with deposit / compensation) to a listing, on top of the game's risks. */
class ListingOwnerRiskIT extends IntegrationTest {

    @Autowired DisputeService disputes;
    @Autowired HandoverService credentials;

    private ListingService.Content content(String title, List<OwnerRisk> ownerRisks) {
        return new ListingService.Content(title, "desc", Map.of(), 30_000, List.of(), 0, List.of(), true,
                EndMode.FORCE_AT_END, ownerRisks);
    }

    private UUID verifiedAccount(UUID owner, UUID gameId) {
        UUID account = gameAccounts.create(owner, gameId, "VN", "player_" + owner.toString().substring(0, 6),
                "secret-pass-1", true, true, true).id();
        gameAccounts.verifyRecovery(user("ADMIN"), account);
        return account;
    }

    @Test
    void ownerRisks_setDeposit_snapshotAndComplaint() {
        UUID gameId = game(); // no staff risks
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID account = verifiedAccount(owner, gameId);

        OwnerRisk skin = new OwnerRisk(null, RiskSide.OWNER, "Mất skin hiếm", "Renter đổi / bán skin", null, true, 150_000L);
        OwnerRisk rank = new OwnerRisk(null, RiskSide.OWNER, "Tụt rank", null, 60, true, 80_000L);
        OwnerRisk login = new OwnerRisk(null, RiskSide.RENTER, "Không vào được game", null, 30, true, 20_000L);

        assertApi(() -> listings.create(owner, account, content("Acc", List.of(skin,
                new OwnerRisk(null, RiskSide.OWNER, " mất SKIN hiếm ", null, null, true, 1L)))), "DUPLICATE_RISK");
        // Owner risks without a deposit: the order would carry none.
        assertApi(() -> listings.create(owner, account, content("Acc", List.of(
                new OwnerRisk(null, RiskSide.OWNER, "Mất skin", null, null, true, null)))), "RISK_DEPOSIT_REQUIRED");

        ListingView l = listings.create(owner, account, content("Acc", List.of(skin, rank, login)));
        assertThat(l.depositAmount()).isEqualTo(230_000); // stackable 150k + 80k
        assertThat(l.ownerRisks()).extracting(GameRiskView::name)
                .containsExactly("Mất skin hiếm", "Tụt rank", "Không vào được game");
        assertThat(l.ownerRisks()).allMatch(GameRiskView::ownerDefined);
        GameRiskView loginRisk = l.ownerRisks().get(2);
        assertThat(l.riskAmount(loginRisk.id())).isEqualTo(20_000L);
        // The game's own list is untouched, and staff editing it leaves the listing's risks alone.
        assertThat(catalog.get(gameId).risks()).isEmpty();
        catalog.replaceRisks(admin, gameId, List.of(new RiskInput(null, RiskSide.RENTER, "Sai mật khẩu", null, null, null)));
        assertThat(listings.get(l.id()).ownerRisks()).hasSize(3);

        listings.submit(owner, l.id());
        listings.approve(admin, l.id());

        // Old clients send no ownerRisks: the risks and their amounts are kept.
        ListingView kept = listings.update(owner, l.id(), null, new ListingService.Content("Acc", "desc", Map.of(),
                30_000, List.of(), 0, List.of(), true, EndMode.FORCE_AT_END));
        assertThat(kept.ownerRisks()).hasSize(3);
        assertThat(kept.depositAmount()).isEqualTo(230_000);
        assertThat(kept.status()).isEqualTo(ListingStatus.ACTIVE);

        Setup s = new Setup(gameId, owner, account, l.id());
        UUID renter = user();
        OrderView o = paidOrder(renter, s, Duration.ofHours(1), 120);
        assertThat(o.depositAmount()).isEqualTo(230_000);
        List<?> snap = (List<?>) o.policySnapshot().get("risks");
        assertThat(snap).hasSize(4); // the game's new risk + the owner's three
        assertThat(snap).anyMatch(m -> ((Map<?, ?>) m).get("name").equals("Không vào được game")
                && Boolean.TRUE.equals(((Map<?, ?>) m).get("ownerDefined"))
                && ((Number) ((Map<?, ?>) m).get("amount")).longValue() == 20_000L);

        // The renter can complain about the owner's own risk like any other.
        advanceAndRun(Duration.ofHours(1));
        credentials.viewCredentials(renter, o.id());
        var d = disputes.open(renter, o.id(), DisputeType.RENTER_RISK, loginRisk.id(), "không vào được", List.of());
        assertThat(d.items()).hasSize(1);
    }

    @Test
    void editingOwnerRiskText_needsReview_amountsDoNot() {
        UUID gameId = game();
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID account = verifiedAccount(owner, gameId);
        ListingView l = listings.create(owner, account, content("Acc", List.of(
                new OwnerRisk(null, RiskSide.OWNER, "Mất skin", null, null, true, 100_000L))));
        listings.submit(owner, l.id());
        listings.approve(admin, l.id());
        UUID riskId = l.ownerRisks().get(0).id();

        // Changing only the amount applies at once.
        ListingView v = listings.update(owner, l.id(), null, content("Acc", List.of(
                new OwnerRisk(riskId, RiskSide.OWNER, "Mất skin", null, null, true, 120_000L))));
        assertThat(v.status()).isEqualTo(ListingStatus.ACTIVE);
        assertThat(v.depositAmount()).isEqualTo(120_000);

        // An unknown id is refused; a new risk (renters read it) sends the listing back to review.
        assertApi(() -> listings.update(owner, l.id(), null, content("Acc", List.of(
                new OwnerRisk(UUID.randomUUID(), RiskSide.OWNER, "X", null, null, true, 100_000L)))), "NOT_FOUND");
        v = listings.update(owner, l.id(), null, content("Acc", List.of(
                new OwnerRisk(riskId, RiskSide.OWNER, "Mất skin", null, null, true, 120_000L),
                new OwnerRisk(null, RiskSide.OWNER, "Bị khóa account", null, null, false, 300_000L))));
        assertThat(v.status()).isEqualTo(ListingStatus.PENDING_REVIEW);
        assertThat(v.depositAmount()).isEqualTo(300_000); // max(stackable 120k, exclusive 300k)

        // Removing a risk archives it and drops its amount.
        v = listings.update(owner, l.id(), null, content("Acc", List.of(
                new OwnerRisk(riskId, RiskSide.OWNER, "Mất skin", null, null, true, 120_000L))));
        assertThat(v.ownerRisks()).hasSize(1);
        assertThat(v.riskTerms()).hasSize(1);
        assertThat(v.depositAmount()).isEqualTo(120_000);
    }
}
