package com.rentgg.gameaccount.domain;

import java.util.Set;

/** GameAccount security_state (§6.3). */
public enum SecurityState {
    PENDING_VERIFICATION,
    AVAILABLE,
    IN_USE,
    AWAITING_RECLAIM,
    ROTATION_PENDING,
    ROTATION_OVERDUE,
    SUSPENDED_SECURITY_REVIEW;

    /** Account takes new bookings only in these states (§6.3). */
    public static final Set<SecurityState> ACCEPTS_ORDERS = Set.of(AVAILABLE, IN_USE);

    public boolean acceptsOrders() {
        return ACCEPTS_ORDERS.contains(this);
    }
}
