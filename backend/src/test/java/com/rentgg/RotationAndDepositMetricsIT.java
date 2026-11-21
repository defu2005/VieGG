package com.rentgg;

import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.support.IntegrationTest;
import com.rentgg.support.IntegrationTest.Setup;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 metrics on /actuator/prometheus: the three that were missing (rotation duration, rotation overdue, deposit
 * count by status) exist from startup and move with the real events. Observability export is switched on for this
 * class only; Boot turns it off in @SpringBootTest by default.
 */
@AutoConfigureObservability
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class RotationAndDepositMetricsIT extends IntegrationTest {

    @Test
    void missingMetricsAreExposedAndMoveWithTheEvents() throws Exception {
        String before = scrape();
        // Exposed from startup, at zero, before any event.
        value(before, "rentgg_rotation_overdue_total", "");
        value(before, "rentgg_rotation_duration_seconds_count", "");
        value(before, "rentgg_deposits_by_status", "status=\"HELD\"");
        value(before, "rentgg_deposits_by_status", "status=\"POST_RENTAL_HOLD\"");
        double overdueBefore = value(before, "rentgg_rotation_overdue_total", "");
        double rotationsBefore = value(before, "rentgg_rotation_duration_seconds_count", "");
        double rotationSecondsBefore = value(before, "rentgg_rotation_duration_seconds_sum", "");

        // Rental ends: the account enters ROTATION_PENDING and the deposit moves to POST_RENTAL_HOLD.
        Setup s = standardListing();
        pay(order(user(), s, Duration.ofHours(1), 180));
        advanceAndRun(Duration.ofHours(1));
        advanceAndRun(Duration.ofHours(3));
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.ROTATION_PENDING);

        String pending = scrape();
        assertThat(value(pending, "rentgg_deposits_by_status", "status=\"POST_RENTAL_HOLD\""))
                .isEqualTo((double) countDeposits("POST_RENTAL_HOLD"));
        assertThat(countDeposits("POST_RENTAL_HOLD")).isGreaterThanOrEqualTo(1);

        // Past the 60-minute SLA the job marks it overdue; the counter counts every overdue account, so require growth.
        advanceAndRun(Duration.ofMinutes(61));
        assertThat(gameAccounts.state(s.accountId())).isEqualTo(SecurityState.ROTATION_OVERDUE);
        assertThat(value(scrape(), "rentgg_rotation_overdue_total", "")).isGreaterThan(overdueBefore);

        // Completing the rotation records one duration sample of at least the 61 minutes it stayed open.
        gameAccounts.rotate(s.ownerId(), s.accountId(), null, "fresh-pass-1", true);
        String after = scrape();
        assertThat(value(after, "rentgg_rotation_duration_seconds_count", "")).isEqualTo(rotationsBefore + 1);
        assertThat(value(after, "rentgg_rotation_duration_seconds_sum", "") - rotationSecondsBefore).isGreaterThanOrEqualTo(3600);
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Long countDeposits(String status) {
        return jdbc.queryForObject("select count(*) from deposits where status = ?", Long.class, status);
    }

    /**
     * Value of one exposed series. Every line carries the common {@code application} tag, so match on the metric name,
     * then on a label fragment (empty means any labels).
     */
    private static double value(String scrape, String name, String labelFragment) {
        for (String line : scrape.split("\n")) {
            if (!line.startsWith(name + "{") && !line.startsWith(name + " ")) {
                continue;
            }
            if (!labelFragment.isEmpty() && !line.startsWith(name + "{") || line.startsWith(name + "{") && !line.contains(labelFragment)) {
                continue;
            }
            return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
        }
        throw new AssertionError("series not exposed: " + name + " " + labelFragment);
    }
}
