package com.rentgg.order.application;

import com.rentgg.order.domain.OrderExtension;
import com.rentgg.payment.application.PaymentCapturedHandler;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OrderPaymentHandler implements PaymentCapturedHandler {

    private final OrderService orders;
    private final OrderExtensionService extensions;

    public OrderPaymentHandler(OrderService orders, OrderExtensionService extensions) {
        this.orders = orders;
        this.extensions = extensions;
    }

    @Override
    public void onPaymentCaptured(UUID intentId, UUID orderId, long amount, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.startsWith(OrderExtension.PAYMENT_KEY_PREFIX)) {
            UUID extensionId = UUID.fromString(idempotencyKey.substring(OrderExtension.PAYMENT_KEY_PREFIX.length()));
            extensions.onPaymentCaptured(intentId, orderId, extensionId, amount);
            return;
        }
        orders.onPaymentCaptured(intentId, orderId, amount);
    }
}
