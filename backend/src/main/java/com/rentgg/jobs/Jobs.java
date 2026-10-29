package com.rentgg.jobs;

import com.rentgg.config.RentGgProperties;
import com.rentgg.deposit.application.DepositService;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.order.application.OrderJobs;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.wallet.application.WalletService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.function.IntSupplier;

/**
 * All periodic jobs of §9 as plain methods (tests call them directly with a moved Clock).
 * {@link JobScheduler} triggers them every minute under ShedLock.
 */
@Service
public class Jobs {

    private static final Logger log = LoggerFactory.getLogger(Jobs.class);

    private final OrderJobs orderJobs;
    private final DepositService deposits;
    private final WalletService wallet;
    private final GameAccountService gameAccounts;
    private final PaymentService payments;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final RentGgProperties props;

    public Jobs(OrderJobs orderJobs, DepositService deposits, WalletService wallet, GameAccountService gameAccounts,
                PaymentService payments, OutboxService outbox, MeterRegistry meters, RentGgProperties props) {
        this.orderJobs = orderJobs;
        this.deposits = deposits;
        this.wallet = wallet;
        this.gameAccounts = gameAccounts;
        this.payments = payments;
        this.outbox = outbox;
        this.meters = meters;
        this.props = props;
    }

    private int batch() {
        return props.jobs().batchSize();
    }

    public int expirePendingPayments() { return run("expirePendingPayments", () -> orderJobs.expirePendingPayments(batch())); }

    public int expireExtensionPayments() {
        return run("expireExtensionPayments", () -> orderJobs.expireExtensionPayments(batch()));
    }

    public int expireOwnerApprovals() { return run("expireOwnerApprovals", () -> orderJobs.expireOwnerApprovals(batch())); }

    public int startDueOrders() { return run("startDueOrders", () -> orderJobs.startDueOrders(batch())); }

    public int endDueOrders() { return run("endDueOrders", () -> orderJobs.endDueOrders(batch())); }

    public int sendEndingReminders() {
        return run("sendEndingReminders", () -> orderJobs.sendEndingReminders(batch(), props.order().endingReminderMinutes()));
    }

    public int releaseSellerHolds() { return run("releaseSellerHolds", () -> wallet.releaseSellerHolds(batch())); }

    public int releaseDeposits() { return run("releaseDeposits", () -> deposits.releaseDue(batch())); }

    public int markRotationOverdue() { return run("markRotationOverdue", () -> gameAccounts.markRotationOverdue(batch())); }

    public int retryFailedRefunds() { return run("retryFailedRefunds", () -> payments.retryFailedRefunds(batch())); }

    public int publishOutbox() { return run("publishOutbox", outbox::publishPending); }

    /** Convenience for tests and the dev endpoint: one full cycle in a sensible order. */
    public void runAll() {
        expirePendingPayments();
        expireExtensionPayments();
        expireOwnerApprovals();
        endDueOrders();
        startDueOrders();
        sendEndingReminders();
        releaseSellerHolds();
        releaseDeposits();
        markRotationOverdue();
        retryFailedRefunds();
        publishOutbox();
    }

    private int run(String name, IntSupplier job) {
        Timer.Sample sample = Timer.start(meters);
        try {
            int n = job.getAsInt();
            meters.counter("rentgg_job_runs_total", "job", name).increment();
            meters.counter("rentgg_job_records_total", "job", name).increment(n);
            return n;
        } catch (RuntimeException e) {
            meters.counter("rentgg_job_failures_total", "job", name).increment();
            log.error("Job {} failed", name, e);
            return 0;
        } finally {
            sample.stop(meters.timer("rentgg_job_duration_seconds", "job", name));
        }
    }
}
