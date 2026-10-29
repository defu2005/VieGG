package com.rentgg.jobs;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cron triggers (every minute by default, rentgg.jobs.cron) guarded by ShedLock. */
@Component
@ConditionalOnProperty(prefix = "rentgg.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class JobScheduler {

    private final Jobs jobs;

    public JobScheduler(Jobs jobs) {
        this.jobs = jobs;
    }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "expirePendingPayments", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void expirePendingPayments() { jobs.expirePendingPayments(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "expireExtensionPayments", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void expireExtensionPayments() { jobs.expireExtensionPayments(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "expireOwnerApprovals", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void expireOwnerApprovals() { jobs.expireOwnerApprovals(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "endDueOrders", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void endDueOrders() { jobs.endDueOrders(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "startDueOrders", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void startDueOrders() { jobs.startDueOrders(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "sendEndingReminders", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void sendEndingReminders() { jobs.sendEndingReminders(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "releaseSellerHolds", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void releaseSellerHolds() { jobs.releaseSellerHolds(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "releaseDeposits", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void releaseDeposits() { jobs.releaseDeposits(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "markRotationOverdue", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void markRotationOverdue() { jobs.markRotationOverdue(); }

    @Scheduled(cron = "${rentgg.jobs.cron}")
    @SchedulerLock(name = "retryFailedRefunds", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void retryFailedRefunds() { jobs.retryFailedRefunds(); }

    @Scheduled(fixedDelayString = "PT10S")
    @SchedulerLock(name = "publishOutbox", lockAtMostFor = "PT2M", lockAtLeastFor = "PT1S")
    public void publishOutbox() { jobs.publishOutbox(); }
}
