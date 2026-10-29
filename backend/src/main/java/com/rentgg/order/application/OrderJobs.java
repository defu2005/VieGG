package com.rentgg.order.application;

import com.rentgg.order.domain.Order;
import com.rentgg.order.infrastructure.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Order jobs of §9. Each record is processed in its own transaction, selected with FOR UPDATE SKIP LOCKED and
 * re-checked after locking, so re-runs and parallel runs are idempotent.
 */
@Service
public class OrderJobs {

    private static final Logger log = LoggerFactory.getLogger(OrderJobs.class);

    private final OrderRepository orders;
    private final OrderService service;
    private final OrderExtensionService extensions;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderJobs(OrderRepository orders, OrderService service, OrderExtensionService extensions,
                     TransactionTemplate tx, Clock clock) {
        this.orders = orders;
        this.service = service;
        this.extensions = extensions;
        this.tx = tx;
        this.clock = clock;
    }

    public int expirePendingPayments(int batch) {
        return each(orders.findPaymentExpired(clock.instant(), batch), service::expireIfDue);
    }

    /** Releases the time reserved by extensions that were not paid before their deadline. */
    public int expireExtensionPayments(int batch) {
        return extensions.expireUnpaid(batch);
    }

    public int expireOwnerApprovals(int batch) {
        return each(orders.findApprovalExpired(clock.instant(), batch), service::rejectIfApprovalTimedOut);
    }

    public int startDueOrders(int batch) {
        return each(orders.findDueToStart(clock.instant(), batch), service::startIfDue);
    }

    public int endDueOrders(int batch) {
        return each(orders.findDueToEnd(clock.instant(), batch), service::endIfDue);
    }

    public int sendEndingReminders(int batch, int reminderMinutes) {
        return each(orders.findNeedingReminder(clock.instant().plus(Duration.ofMinutes(reminderMinutes)), batch),
                service::remindIfDue);
    }

    private int each(List<UUID> ids, Predicate<Order> step) {
        int n = 0;
        for (UUID id : ids) {
            try {
                Boolean done = tx.execute(s -> orders.lockForJob(id).map(step::test).orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    n++;
                }
            } catch (RuntimeException e) {
                log.error("Order job step failed for order {}", id, e);
            }
        }
        return n;
    }
}
