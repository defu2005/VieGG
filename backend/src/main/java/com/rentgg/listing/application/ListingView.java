package com.rentgg.listing.application;

import com.rentgg.catalog.application.GameRiskView;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.Listing;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.listing.domain.PricingTier;
import com.rentgg.listing.domain.RiskTerm;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code imageIds} are in display order; the bytes are served by GET /listings/{id}/images/{imageId}.
 * {@code pricingTiers} are the rental packages (sorted by duration); empty means rent = pricePerHour × duration.
 * {@code ownerRisks}: risks the owner added to this listing on top of the game's; riskTerms hold their amounts too.
 */
public record ListingView(UUID id, UUID gameAccountId, UUID ownerId, UUID gameId, String title, String description,
                          Map<String, Object> attributes, long pricePerHour, List<PricingTier> pricingTiers,
                          long depositAmount, List<RiskTerm> riskTerms, boolean autoAccept, EndMode endMode,
                          ListingStatus status, String reviewNote, Long version, Instant createdAt,
                          List<UUID> imageIds, List<GameRiskView> ownerRisks,
                          /** Owner's free-cancel window in minutes; null = the game's default. */
                          Integer renterFreeCancelMinutes) {

    static ListingView of(Listing l, List<UUID> imageIds, List<GameRiskView> ownerRisks) {
        return new ListingView(l.getId(), l.getGameAccountId(), l.getOwnerId(), l.getGameId(), l.getTitle(),
                l.getDescription(), l.getAttributes(), l.getPricePerHour(), l.getPricingTiers(), l.getDepositAmount(),
                l.getRiskTerms(), l.isAutoAccept(), l.getEndMode(), l.getStatus(), l.getReviewNote(), l.getVersion(),
                l.getCreatedAt(), imageIds == null ? List.of() : List.copyOf(imageIds),
                ownerRisks == null ? List.of() : List.copyOf(ownerRisks), l.getRenterFreeCancelMinutes());
    }

    /** The free-cancel window that applies to new orders: the owner's choice, else the game's default. */
    public int effectiveRenterFreeCancelMinutes(int gameDefault) {
        return renterFreeCancelMinutes != null ? renterFreeCancelMinutes : gameDefault;
    }

    /** The owner's amount for this risk, or null (no term / no compensation). */
    public Long riskAmount(UUID riskId) {
        return riskTerms.stream().filter(t -> t.riskId().equals(riskId)).map(RiskTerm::amount)
                .findFirst().orElse(null);
    }

    /** Price of the package with exactly this duration, or null when the listing has no such package. */
    public Long tierPrice(int durationMinutes) {
        return pricingTiers.stream().filter(t -> t.durationMinutes() == durationMinutes)
                .map(PricingTier::price).findFirst().orElse(null);
    }
}
