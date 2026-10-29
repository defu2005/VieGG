package com.rentgg.ledger.domain;

/** Ledger transaction types of §7.3. */
public enum TxType {
    PAYMENT_CAPTURED,
    ORDER_ENDED,
    SELLER_HOLD_RELEASED,
    SELLER_DISPUTE_HOLD,
    SELLER_DISPUTE_HOLD_RELEASED,
    DEPOSIT_REFUND_REQUESTED,
    REFUND_COMPLETED,
    DEPOSIT_DEDUCTED,
    RENT_REFUND,
    ORDER_CANCELLED_REFUND,
    ORDER_CANCELLED_REVENUE,
    WITHDRAWAL_REQUESTED,
    WITHDRAWAL_PAID,
    WITHDRAWAL_FAILED,
    LEDGER_ADJUSTMENT,
    /** PSP money in → the user's wallet. */
    WALLET_TOPUP,
    /** The user's wallet pays an order or extension; PAYMENT_CAPTURED then moves it to escrow. */
    WALLET_PAYMENT,
    /** RENTER risk confirmed: the owner's money (dispute hold of the order, then wallet) → the renter's wallet. */
    RISK_COMPENSATION
}
