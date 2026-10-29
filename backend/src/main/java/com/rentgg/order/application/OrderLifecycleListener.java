package com.rentgg.order.application;

/**
 * Port for higher modules (dispute) that must react synchronously, in the same transaction, when an order ends
 * (e.g. move revenue to SELLER_DISPUTE_HOLD while a renter dispute is open, §8.9).
 */
public interface OrderLifecycleListener {

    void afterOrderEnded(OrderView order);
}
