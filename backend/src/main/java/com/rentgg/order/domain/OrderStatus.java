package com.rentgg.order.domain;

import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, AWAITING_OWNER_APPROVAL, CONFIRMED, IN_RENTAL, ENDED, CANCELLED, EXPIRED, REJECTED;

    /** Statuses that hold the slot (exclusion constraint WHERE clause). */
    public static final Set<OrderStatus> ACTIVE = Set.of(PENDING_PAYMENT, AWAITING_OWNER_APPROVAL, CONFIRMED, IN_RENTAL);
}
