package com.rentgg.catalog.application;

import com.rentgg.catalog.domain.AccountKeyRule;
import com.rentgg.catalog.domain.Game;
import com.rentgg.catalog.domain.GameRiskLevel;
import com.rentgg.catalog.domain.HandoverMode;
import com.rentgg.catalog.domain.SuggestedPackage;
import com.rentgg.catalog.domain.TosStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Public view of a game. Risk level, notes and risks are public on purpose: owners price deposits from them and
 * renters see what each deposit / compensation covers. {@code risks} are the active ones, in display order.
 * {@code suggestedPackages}: rental packages staff suggest; owners decide which ones a listing offers.
 */
public record GameView(UUID id, String code, String name, String publisher, TosStatus tosStatus, String tosUrl,
                       Instant tosReviewedAt, HandoverMode handoverMode, boolean passwordChangeKicksSessions,
                       long depositMin, long depositMax, int depositHoldHours, int sellerHoldHours,
                       int sellerHoldHoursHighRisk, int complaintWindowMinutes, int minDurationMinutes,
                       int maxDurationMinutes, int graceMinutes, int platformFeeBps, int renterFreeCancelMinutes,
                       int lateCancelRefundBps, int ownerFreeCancelHours, int minAge,
                       GameRiskLevel riskLevel, String riskNotes, List<GameRiskView> risks,
                       AccountKeyRule accountKey, String accountKeyLabel,
                       List<SuggestedPackage> suggestedPackages) {

    public static GameView of(Game g, List<GameRiskView> risks) {
        return new GameView(g.getId(), g.getCode(), g.getName(), g.getPublisher(), g.getTosStatus(), g.getTosUrl(),
                g.getTosReviewedAt(), g.getHandoverMode(), g.isPasswordChangeKicksSessions(), g.getDepositMin(),
                g.getDepositMax(), g.getDepositHoldHours(), g.getSellerHoldHours(), g.getSellerHoldHoursHighRisk(),
                g.getComplaintWindowMinutes(), g.getMinDurationMinutes(), g.getMaxDurationMinutes(),
                g.getGraceMinutes(), g.getPlatformFeeBps(), g.getRenterFreeCancelMinutes(), g.getLateCancelRefundBps(),
                g.getOwnerFreeCancelHours(), g.getMinAge(), g.getRiskLevel(), g.getRiskNotes(),
                risks == null ? List.of() : List.copyOf(risks), g.getAccountKeyRule(),
                g.getAccountKeyRule().displayLabel(), g.getSuggestedPackages());
    }

    public boolean allowed() {
        return tosStatus == TosStatus.ALLOWED;
    }

    /** This game plus the risks the owner added to one listing (after the game's own): what applies to that listing. */
    public GameView withListingRisks(List<GameRiskView> listingRisks) {
        if (listingRisks == null || listingRisks.isEmpty()) {
            return this;
        }
        List<GameRiskView> all = new java.util.ArrayList<>(risks);
        all.addAll(listingRisks);
        return new GameView(id, code, name, publisher, tosStatus, tosUrl, tosReviewedAt, handoverMode,
                passwordChangeKicksSessions, depositMin, depositMax, depositHoldHours, sellerHoldHours,
                sellerHoldHoursHighRisk, complaintWindowMinutes, minDurationMinutes, maxDurationMinutes, graceMinutes,
                platformFeeBps, renterFreeCancelMinutes, lateCancelRefundBps, ownerFreeCancelHours, minAge, riskLevel,
                riskNotes, List.copyOf(all), accountKey, accountKeyLabel, suggestedPackages);
    }

    public GameRiskView risk(UUID riskId) {
        return risks.stream().filter(r -> r.id().equals(riskId)).findFirst().orElse(null);
    }

    public boolean hasOwnerRisks() {
        return risks.stream().anyMatch(r -> r.side() == com.rentgg.catalog.domain.RiskSide.OWNER);
    }
}
