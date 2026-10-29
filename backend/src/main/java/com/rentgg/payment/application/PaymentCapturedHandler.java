package com.rentgg.payment.application;

import java.util.UUID;

/**
 * Port implemented by the order module: called inside the webhook transaction when a payment succeeds,
 * so the order transition and ledger posting commit atomically with the intent update (§8.1).
 * {@code idempotencyKey} is the key the order module created the checkout with; it tells the order's own payment
 * apart from an extension payment.
 */
public interface PaymentCapturedHandler {

    void onPaymentCaptured(UUID intentId, UUID orderId, long amount, String idempotencyKey);
}
