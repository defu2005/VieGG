package com.rentgg.catalog.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Editable per-game parameters with the constraints of §14.1. */
public record GamePolicy(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 120) String publisher,
        String tosUrl,
        @NotNull HandoverMode handoverMode,
        boolean passwordChangeKicksSessions,
        @Positive long depositMin,
        @Positive long depositMax,
        @Min(1) @Max(720) int depositHoldHours,
        @Min(0) @Max(720) int sellerHoldHours,
        @Min(0) @Max(720) int sellerHoldHoursHighRisk,
        @Min(5) @Max(120) int complaintWindowMinutes,
        @Positive int minDurationMinutes,
        @Positive int maxDurationMinutes,
        @Min(0) @Max(30) int graceMinutes,
        @Min(0) @Max(5000) int platformFeeBps,
        @Min(0) int renterFreeCancelMinutes,
        @Min(0) @Max(10000) int lateCancelRefundBps,
        @Min(0) int ownerFreeCancelHours,
        @Min(18) int minAge,
        @Size(max = 2000) String riskNotes,
        /** Null keeps the game's current level (MEDIUM for a new game). */
        GameRiskLevel riskLevel,
        /** What makes one game account unique. Null keeps the game's current rule (LOGIN for a new game). */
        @Valid AccountKeyRule accountKey) {

    /** Policy without an account-key change (keeps the game's current rule). */
    public GamePolicy(String name, String publisher, String tosUrl, HandoverMode handoverMode,
                      boolean passwordChangeKicksSessions, long depositMin, long depositMax, int depositHoldHours,
                      int sellerHoldHours, int sellerHoldHoursHighRisk, int complaintWindowMinutes,
                      int minDurationMinutes, int maxDurationMinutes, int graceMinutes, int platformFeeBps,
                      int renterFreeCancelMinutes, int lateCancelRefundBps, int ownerFreeCancelHours, int minAge,
                      String riskNotes, GameRiskLevel riskLevel) {
        this(name, publisher, tosUrl, handoverMode, passwordChangeKicksSessions, depositMin, depositMax,
                depositHoldHours, sellerHoldHours, sellerHoldHoursHighRisk, complaintWindowMinutes, minDurationMinutes,
                maxDurationMinutes, graceMinutes, platformFeeBps, renterFreeCancelMinutes, lateCancelRefundBps,
                ownerFreeCancelHours, minAge, riskNotes, riskLevel, null);
    }

    @AssertTrue(message = "Cọc tối đa phải lớn hơn hoặc bằng cọc tối thiểu.")
    public boolean isDepositRangeValid() {
        return depositMax >= depositMin;
    }

    @AssertTrue(message = "Thời lượng tối đa phải lớn hơn hoặc bằng thời lượng tối thiểu.")
    public boolean isDurationRangeValid() {
        return maxDurationMinutes >= minDurationMinutes;
    }

    @AssertTrue(message = "Thời gian giữ doanh thu cho seller rủi ro cao phải lớn hơn hoặc bằng thời gian giữ doanh thu thường.")
    public boolean isSellerHoldValid() {
        return sellerHoldHoursHighRisk >= sellerHoldHours;
    }

    /** Defaults of §14.1 with the given name and deposit range. */
    public static GamePolicy defaults(String name, long depositMin, long depositMax) {
        return new GamePolicy(name, null, null, HandoverMode.STATIC_CREDENTIAL, false, depositMin, depositMax,
                72, 72, 168, 15, 60, 1440, 10, 1000, 120, 5000, 12, 18, null, GameRiskLevel.MEDIUM);
    }

    /** Same policy with another risk level and description. */
    public GamePolicy withRisk(GameRiskLevel level, String notes) {
        return new GamePolicy(name, publisher, tosUrl, handoverMode, passwordChangeKicksSessions, depositMin, depositMax,
                depositHoldHours, sellerHoldHours, sellerHoldHoursHighRisk, complaintWindowMinutes, minDurationMinutes,
                maxDurationMinutes, graceMinutes, platformFeeBps, renterFreeCancelMinutes, lateCancelRefundBps,
                ownerFreeCancelHours, minAge, notes, level, accountKey);
    }

    /** Same policy with another account identity rule. */
    public GamePolicy withAccountKey(AccountKeyRule rule) {
        return new GamePolicy(name, publisher, tosUrl, handoverMode, passwordChangeKicksSessions, depositMin, depositMax,
                depositHoldHours, sellerHoldHours, sellerHoldHoursHighRisk, complaintWindowMinutes, minDurationMinutes,
                maxDurationMinutes, graceMinutes, platformFeeBps, renterFreeCancelMinutes, lateCancelRefundBps,
                ownerFreeCancelHours, minAge, riskNotes, riskLevel, rule);
    }
}
