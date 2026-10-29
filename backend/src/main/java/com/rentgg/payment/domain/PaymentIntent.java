package com.rentgg.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    public static final String CREATED = "CREATED";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String EXPIRED = "EXPIRED";
    /** Provider of payments taken from the user's wallet balance. */
    public static final String WALLET = "WALLET";

    @Id
    private UUID id;
    private UUID orderId;
    private UUID payerId;
    private String provider;
    private String providerRef;
    private long amount;
    private String status;
    private String checkoutUrl;
    private String idempotencyKey;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected PaymentIntent() {}

    public PaymentIntent(UUID orderId, UUID payerId, String provider, long amount, String idempotencyKey, Instant now) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.payerId = payerId;
        this.provider = provider;
        this.amount = amount;
        this.status = CREATED;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void attachCheckout(String providerRef, String checkoutUrl, Instant now) {
        this.providerRef = providerRef;
        this.checkoutUrl = checkoutUrl;
        this.updatedAt = now;
    }

    public void setStatus(String status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getPayerId() { return payerId; }
    public String getProvider() { return provider; }
    public String getProviderRef() { return providerRef; }
    public long getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public String getIdempotencyKey() { return idempotencyKey; }
    /** A wallet top-up has no order. */
    public boolean isTopup() { return orderId == null; }
    public Instant getCreatedAt() { return createdAt; }
}
