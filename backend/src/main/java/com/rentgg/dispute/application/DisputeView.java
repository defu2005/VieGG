package com.rentgg.dispute.application;

import com.rentgg.dispute.domain.Dispute;
import com.rentgg.dispute.domain.DisputeItem;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.dispute.domain.DisputeType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code items}: the risks of a renter complaint (RENTER_RISK), each decided on its own; empty for other types. */
public record DisputeView(UUID id, UUID orderId, UUID openedBy, DisputeType type, DisputeStatus status,
                          String description, List<Map<String, Object>> evidence, Instant deadlineAt,
                          long depositDeduction, long rentRefund, UUID decidedBy, Instant decidedAt, String reasonCode,
                          String decisionNote, Instant createdAt, UUID riskId, long compensation, List<Item> items) {

    public record Item(UUID id, UUID riskId, DisputeItem.Status status, long compensation, String reasonCode,
                       String note, UUID decidedBy, Instant decidedAt) {

        static Item of(DisputeItem i) {
            return new Item(i.getId(), i.getRiskId(), i.getStatus(), i.getCompensation(), i.getReasonCode(),
                    i.getNote(), i.getDecidedBy(), i.getDecidedAt());
        }
    }

    static DisputeView of(Dispute d, List<DisputeItem> items) {
        return new DisputeView(d.getId(), d.getOrderId(), d.getOpenedBy(), d.getType(), d.getStatus(),
                d.getDescription(), d.getEvidence(), d.getDeadlineAt(), d.getDepositDeduction(), d.getRentRefund(),
                d.getDecidedBy(), d.getDecidedAt(), d.getReasonCode(), d.getDecisionNote(), d.getCreatedAt(),
                d.getRiskId(), d.getCompensation(), items.stream().map(Item::of).toList());
    }
}
