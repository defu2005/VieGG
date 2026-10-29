package com.rentgg.order.domain;

import com.rentgg.listing.domain.EndMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Rental order. rental_period is a DB-generated column and is intentionally not mapped. */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;
    private UUID listingId;
    private UUID gameAccountId;
    private UUID gameId;
    private UUID renterId;
    private UUID ownerId;
    @Enumerated(EnumType.STRING)
    private OrderStatus status;
    private Instant startAt;
    private Instant scheduledEndAt;
    private Instant blockEndAt;
    private Instant actualEndAt;
    private long rentAmount;
    private long platformFee;
    private long sellerNet;
    private long depositAmount;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> policySnapshot;
    private Instant paymentExpiresAt;
    private Instant approvalExpiresAt;
    private Instant credentialFirstViewedAt;
    private Instant handoverConfirmedAt;
    private String otpNote;
    private Instant otpNoteExpiresAt;
    @Enumerated(EnumType.STRING)
    private CancelReason cancelReason;
    private UUID cancelledBy;
    private Instant sellerHoldUntil;
    private Instant sellerReleasedAt;
    private Instant endingReminderSentAt;
    private Instant extensionHoldUntil;
    private int extendedMinutes;
    private long extensionRentAmount;
    private String idempotencyKey;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Order() {}

    public Order(UUID listingId, UUID gameAccountId, UUID gameId, UUID renterId, UUID ownerId, Instant startAt,
                 Instant scheduledEndAt, Instant blockEndAt, long rentAmount, long platformFee, long depositAmount,
                 Map<String, Object> policySnapshot, Instant paymentExpiresAt, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.listingId = listingId;
        this.gameAccountId = gameAccountId;
        this.gameId = gameId;
        this.renterId = renterId;
        this.ownerId = ownerId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.startAt = startAt;
        this.scheduledEndAt = scheduledEndAt;
        this.blockEndAt = blockEndAt;
        this.rentAmount = rentAmount;
        this.platformFee = platformFee;
        this.sellerNet = rentAmount - platformFee;
        this.depositAmount = depositAmount;
        this.policySnapshot = policySnapshot;
        this.paymentExpiresAt = paymentExpiresAt;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    // ---------------------------------------------------------------- policy snapshot accessors

    public int policyInt(String key) {
        return ((Number) policySnapshot.get(key)).intValue();
    }

    public boolean policyBool(String key) {
        return Boolean.TRUE.equals(policySnapshot.get(key));
    }

    public String policyString(String key) {
        Object v = policySnapshot.get(key);
        return v == null ? null : v.toString();
    }

    /** True when the rental starts as soon as it is paid (and approved), instead of at a chosen time. */
    public boolean startsOnPayment() {
        return policyBool("startOnPayment");
    }

    public EndMode endMode() {
        return EndMode.valueOf(policyString("endMode"));
    }

    public Instant complaintWindowEndsAt() {
        return credentialFirstViewedAt == null ? null
                : credentialFirstViewedAt.plusSeconds(60L * policyInt("complaintWindowMinutes"));
    }

    // ---------------------------------------------------------------- mutators

    public void moveTo(OrderStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    public void cancel(OrderStatus terminal, CancelReason reason, UUID by, Instant now) {
        this.status = terminal;
        this.cancelReason = reason;
        this.cancelledBy = by;
        this.updatedAt = now;
    }

    public void setCancelReasonIfAbsent(CancelReason reason, Instant now) {
        if (this.cancelReason == null) {
            this.cancelReason = reason;
            this.updatedAt = now;
        }
    }

    public void awaitApproval(Instant approvalExpiresAt, Instant now) {
        this.status = OrderStatus.AWAITING_OWNER_APPROVAL;
        this.approvalExpiresAt = approvalExpiresAt;
        this.updatedAt = now;
    }

    /**
     * Moves a "start on payment" order to start now. The new period always stays inside the slot reserved at
     * creation (only ever shrinks), so it can never collide with another booking.
     */
    public void startNowWithin(Instant now, int durationMinutes, int graceMinutes) {
        Instant latestScheduledEnd = blockEndAt.minusSeconds(60L * graceMinutes);
        Instant end = now.plusSeconds(60L * durationMinutes);
        if (end.isAfter(latestScheduledEnd)) {
            end = latestScheduledEnd;
        }
        if (!end.isAfter(now)) {
            return;
        }
        this.startAt = now;
        this.scheduledEndAt = end;
        this.blockEndAt = end.plusSeconds(60L * graceMinutes);
        this.updatedAt = now;
    }

    /** Reserves the calendar up to {@code until} while an extension is being paid (checked by the exclusion constraint). */
    public void holdForExtension(Instant until, Instant now) {
        this.extensionHoldUntil = until;
        this.updatedAt = now;
    }

    public void releaseExtensionHold(Instant now) {
        this.extensionHoldUntil = null;
        this.updatedAt = now;
    }

    /** A paid extension: the rental now ends later and costs more. The deposit does not change. */
    public void applyExtension(OrderExtension e, Instant now) {
        this.scheduledEndAt = e.getNewScheduledEndAt();
        this.blockEndAt = e.getNewBlockEndAt();
        this.rentAmount += e.getRentAmount();
        this.platformFee += e.getPlatformFee();
        this.sellerNet = rentAmount - platformFee;
        this.extendedMinutes += e.getDurationMinutes();
        this.extensionRentAmount += e.getRentAmount();
        this.extensionHoldUntil = null;
        // Remind again before the new end time.
        this.endingReminderSentAt = null;
        this.updatedAt = now;
    }

    /** Rent of the original booking, i.e. what the order's own (first) payment covered. */
    public long initialRentAmount() {
        return rentAmount - extensionRentAmount;
    }

    public void end(Instant now) {
        this.status = OrderStatus.ENDED;
        this.extensionHoldUntil = null;
        this.actualEndAt = now;
        this.otpNote = null;
        this.otpNoteExpiresAt = null;
        this.updatedAt = now;
    }

    public boolean recordFirstView(Instant now) {
        if (credentialFirstViewedAt == null) {
            credentialFirstViewedAt = now;
            updatedAt = now;
            return true;
        }
        return false;
    }

    public boolean confirmHandover(Instant now) {
        if (handoverConfirmedAt == null) {
            handoverConfirmedAt = now;
            updatedAt = now;
            return true;
        }
        return false;
    }

    public void setOtpNote(String note, Instant expiresAt, Instant now) {
        this.otpNote = note;
        this.otpNoteExpiresAt = expiresAt;
        this.updatedAt = now;
    }

    public String activeOtpNote(Instant now) {
        return otpNote != null && otpNoteExpiresAt != null && otpNoteExpiresAt.isAfter(now) ? otpNote : null;
    }

    public void setSellerHoldUntil(Instant until) {
        this.sellerHoldUntil = until;
    }

    public void markSellerReleased(Instant now) {
        this.sellerReleasedAt = now;
        this.updatedAt = now;
    }

    public void markEndingReminderSent(Instant now) {
        this.endingReminderSentAt = now;
        this.updatedAt = now;
    }

    // ---------------------------------------------------------------- getters

    public UUID getId() { return id; }
    public UUID getListingId() { return listingId; }
    public UUID getGameAccountId() { return gameAccountId; }
    public UUID getGameId() { return gameId; }
    public UUID getRenterId() { return renterId; }
    public UUID getOwnerId() { return ownerId; }
    public OrderStatus getStatus() { return status; }
    public Instant getStartAt() { return startAt; }
    public Instant getScheduledEndAt() { return scheduledEndAt; }
    public Instant getBlockEndAt() { return blockEndAt; }
    public Instant getActualEndAt() { return actualEndAt; }
    public long getRentAmount() { return rentAmount; }
    public long getPlatformFee() { return platformFee; }
    public long getSellerNet() { return sellerNet; }
    public long getDepositAmount() { return depositAmount; }
    public Map<String, Object> getPolicySnapshot() { return policySnapshot; }
    public Instant getPaymentExpiresAt() { return paymentExpiresAt; }
    public Instant getApprovalExpiresAt() { return approvalExpiresAt; }
    public Instant getCredentialFirstViewedAt() { return credentialFirstViewedAt; }
    public Instant getHandoverConfirmedAt() { return handoverConfirmedAt; }
    public CancelReason getCancelReason() { return cancelReason; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getSellerHoldUntil() { return sellerHoldUntil; }
    public Instant getSellerReleasedAt() { return sellerReleasedAt; }
    public Instant getEndingReminderSentAt() { return endingReminderSentAt; }
    public Instant getExtensionHoldUntil() { return extensionHoldUntil; }
    public int getExtendedMinutes() { return extendedMinutes; }
    public long getExtensionRentAmount() { return extensionRentAmount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
