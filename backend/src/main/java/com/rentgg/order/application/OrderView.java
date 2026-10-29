package com.rentgg.order.application;

import com.rentgg.listing.domain.EndMode;
import com.rentgg.order.domain.CancelReason;
import com.rentgg.order.domain.Order;
import com.rentgg.order.domain.OrderStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Order read model (never includes credentials). */
public record OrderView(UUID id, UUID listingId, UUID gameAccountId, UUID gameId, UUID renterId, UUID ownerId,
                        OrderStatus status, Instant startAt, Instant scheduledEndAt, Instant blockEndAt,
                        Instant actualEndAt, long rentAmount, long platformFee, long sellerNet, long depositAmount,
                        long amountToPay, EndMode endMode, Instant paymentExpiresAt, Instant approvalExpiresAt,
                        Instant credentialFirstViewedAt, Instant complaintWindowEndsAt, Instant handoverConfirmedAt,
                        CancelReason cancelReason, Instant sellerHoldUntil, Instant sellerReleasedAt,
                        Map<String, Object> policySnapshot, int extendedMinutes, long extensionRentAmount,
                        Long version, Instant createdAt) {

    public static OrderView of(Order o) {
        return new OrderView(o.getId(), o.getListingId(), o.getGameAccountId(), o.getGameId(), o.getRenterId(),
                o.getOwnerId(), o.getStatus(), o.getStartAt(), o.getScheduledEndAt(), o.getBlockEndAt(),
                o.getActualEndAt(), o.getRentAmount(), o.getPlatformFee(), o.getSellerNet(), o.getDepositAmount(),
                o.getRentAmount() + o.getDepositAmount(), o.endMode(), o.getPaymentExpiresAt(),
                o.getApprovalExpiresAt(), o.getCredentialFirstViewedAt(), o.complaintWindowEndsAt(),
                o.getHandoverConfirmedAt(), o.getCancelReason(), o.getSellerHoldUntil(), o.getSellerReleasedAt(),
                o.getPolicySnapshot(), o.getExtendedMinutes(), o.getExtensionRentAmount(), o.getVersion(),
                o.getCreatedAt());
    }

    public int policyInt(String key) {
        return ((Number) policySnapshot.get(key)).intValue();
    }
}
