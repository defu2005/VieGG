package com.rentgg.order.domain;

/**
 * Money formulas of §7.3 (VND, integer, floor; the remainder goes to the seller because net = rent − fee).
 */
public final class Money {

    private Money() {}

    public static long rent(long pricePerHour, int durationMinutes) {
        return Math.floorDiv(Math.multiplyExact(pricePerHour, (long) durationMinutes), 60L);
    }

    public static long bps(long amount, int bps) {
        return Math.floorDiv(Math.multiplyExact(amount, (long) bps), 10_000L);
    }

    public static long fee(long rent, int platformFeeBps) {
        return bps(rent, platformFeeBps);
    }

    /** Commission refunded proportionally with a rent refund: floor(r × fee / rent). */
    public static long proportionalFee(long refund, long fee, long rent) {
        return rent == 0 ? 0 : Math.floorDiv(Math.multiplyExact(refund, fee), rent);
    }
}
