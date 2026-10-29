package com.rentgg.ledger.domain;

/**
 * Platform fee on every risk payout: a deposit deduction paid to the owner (OWNER risk) and the owner's
 * compensation paid to the renter (RENTER risk). The payer pays the full amount; the platform keeps
 * {@link #BPS} of it and the recipient gets the rest. VND, floor.
 */
public final class RiskPayoutFee {

    /** 10%. */
    public static final int BPS = 1000;

    private RiskPayoutFee() {}

    public static long of(long amount) {
        return Math.floorDiv(Math.multiplyExact(amount, (long) BPS), 10_000L);
    }
}
