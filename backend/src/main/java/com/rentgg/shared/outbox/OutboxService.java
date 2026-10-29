package com.rentgg.shared.outbox;

import com.rentgg.config.RentGgProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox (§10): business changes and the event row commit together; {@link #publishPending()}
 * later turns rows into Spring application events.
 */
@Service
public class OutboxService {

    private static final Logger log = LoggerFactory.getLogger(OutboxService.class);

    private final OutboxRepository repository;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RentGgProperties props;

    public OutboxService(OutboxRepository repository, ApplicationEventPublisher publisher, TransactionTemplate tx,
                         Clock clock, RentGgProperties props) {
        this.repository = repository;
        this.publisher = publisher;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    /** Must be called inside the business transaction. Payload must never contain secrets. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        Map<String, Object> copy = new LinkedHashMap<>();
        payload.forEach((k, v) -> copy.put(k, v instanceof Instant i ? i.toString()
                : v instanceof UUID u ? u.toString()
                : v instanceof Enum<?> e ? e.name() : v));
        repository.save(new OutboxEvent(aggregateType, aggregateId, eventType, copy, clock.instant()));
    }

    /** Publishes pending events, one transaction per event. Returns the number published. */
    public int publishPending() {
        int published = 0;
        int batch = props.jobs().batchSize();
        // Loop a few rounds so events produced by consumers (e.g. RefundCompleted) are delivered promptly.
        for (int round = 0; round < 5; round++) {
            List<UUID> ids = repository.findPublishable(clock.instant(), props.outbox().maxAttempts(), batch);
            if (ids.isEmpty()) {
                break;
            }
            for (UUID id : ids) {
                if (publishOne(id)) {
                    published++;
                }
            }
        }
        return published;
    }

    private boolean publishOne(UUID id) {
        try {
            Boolean ok = tx.execute(status -> repository.lockUnpublished(id).map(e -> {
                publisher.publishEvent(new DomainEvent(e.getId(), e.getAggregateType(), e.getAggregateId(),
                        e.getEventType(), e.getPayload(), e.getCreatedAt()));
                e.markPublished(clock.instant());
                return true;
            }).orElse(false));
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException ex) {
            log.warn("Outbox event {} failed: {}", id, ex.toString());
            tx.executeWithoutResult(status -> repository.findById(id).ifPresent(e -> {
                long backoffSeconds = Math.min(3600, (long) Math.pow(2, e.getAttempts() + 1));
                e.markFailed(clock.instant().plus(Duration.ofSeconds(backoffSeconds)), ex.toString());
                if (e.getAttempts() >= props.outbox().maxAttempts()) {
                    log.error("Outbox event {} ({}) is stuck after {} attempts", id, e.getEventType(), e.getAttempts());
                }
            }));
            return false;
        }
    }
}
