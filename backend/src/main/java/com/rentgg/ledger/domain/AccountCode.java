package com.rentgg.ledger.domain;

/**
 * Ledger accounts of §7.2. SELLER_* accounts are per user (owner_user_id). SELLER_AVAILABLE is the user's wallet,
 * renter or owner alike: top-ups, refunds and released revenue land there; order payments and withdrawals take from it.
 */
public enum AccountCode {
    PSP_CLEARING,
    RENT_ESCROW,
    DEPOSIT_HELD,
    REFUND_PAYABLE,
    PLATFORM_REVENUE,
    SELLER_REVIEW_HOLD,
    SELLER_DISPUTE_HOLD,
    SELLER_AVAILABLE,
    SELLER_PAYOUT_PROCESSING;

    public boolean perSeller() {
        return name().startsWith("SELLER_");
    }
}
