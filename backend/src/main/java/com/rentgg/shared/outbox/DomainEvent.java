package com.rentgg.shared.outbox;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** In-process event published from the outbox (at-least-once). Consumers must be idempotent on {@link #id()}. */
public record DomainEvent(UUID id, String aggregateType, UUID aggregateId, String eventType,
                          Map<String, Object> payload, Instant createdAt) {

    public boolean is(String type) {
        return eventType.equals(type);
    }

    public UUID uuid(String key) {
        Object v = payload.get(key);
        return v == null ? null : UUID.fromString(v.toString());
    }

    public long amount(String key) {
        Object v = payload.get(key);
        return v == null ? 0L : ((Number) v).longValue();
    }

    public String string(String key) {
        Object v = payload.get(key);
        return v == null ? null : v.toString();
    }
}
