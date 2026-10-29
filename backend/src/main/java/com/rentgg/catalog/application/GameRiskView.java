package com.rentgg.catalog.application;

import com.rentgg.catalog.domain.GameRisk;
import com.rentgg.catalog.domain.RiskSide;

import java.util.UUID;

/**
 * A risk of a game. {@code recommendation} is staff's free-text advice on the deposit (OWNER) or compensation
 * (RENTER), shown to owners when they set their amounts; null = none.
 * {@code complaintWindowMinutes} null means no time limit beyond the order's own rules.
 * {@code stackable} (OWNER risks): true = deductions add up with other stackable risks; false = exclusive, the
 * renter pays only this risk's deposit when it happens.
 * {@code ownerDefined}: added by the listing's owner for that listing only, not by staff for the whole game.
 */
public record GameRiskView(UUID id, RiskSide side, String name, String description, String recommendation,
                           Integer complaintWindowMinutes, boolean stackable, boolean ownerDefined) {

    public static GameRiskView of(GameRisk r) {
        return new GameRiskView(r.getId(), r.getSide(), r.getName(), r.getDescription(), r.getRecommendation(),
                r.getComplaintWindowMinutes(), r.isStackable(), r.getListingId() != null);
    }
}
