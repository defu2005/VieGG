package com.rentgg.jobs.devtime;

import com.rentgg.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DevClockTest {

    private static final Instant T0 = Instant.parse("2026-10-28T03:00:00Z");

    @TempDir
    Path dir;

    @Test
    void movedTimeKeepsTickingWithRealTime() {
        MutableClock real = new MutableClock(T0);
        DevClock clock = new DevClock(real, dir.resolve("clock.properties"));

        clock.set(T0.plus(Duration.ofDays(2)));
        real.advance(Duration.ofMinutes(5));

        assertThat(clock.instant()).isEqualTo(T0.plus(Duration.ofDays(2)).plus(Duration.ofMinutes(5)));
        assertThat(clock.offset()).isEqualTo(Duration.ofDays(2));
    }

    @Test
    void frozenTimeStandsStillUntilUnfrozen() {
        MutableClock real = new MutableClock(T0);
        DevClock clock = new DevClock(real, dir.resolve("clock.properties"));

        clock.freeze(true);
        real.advance(Duration.ofHours(1));
        assertThat(clock.instant()).isEqualTo(T0);

        clock.set(T0.plus(Duration.ofHours(3)));
        real.advance(Duration.ofHours(1));
        assertThat(clock.instant()).isEqualTo(T0.plus(Duration.ofHours(3)));

        clock.freeze(false);
        real.advance(Duration.ofMinutes(1));
        assertThat(clock.instant()).isEqualTo(T0.plus(Duration.ofHours(3)).plusSeconds(60));
    }

    @Test
    void stateSurvivesARestartAndResetReturnsToRealTime() {
        Path file = dir.resolve("clock.properties");
        MutableClock real = new MutableClock(T0);
        new DevClock(real, file).set(T0.plus(Duration.ofDays(7)));

        DevClock restarted = new DevClock(real, file);
        assertThat(restarted.instant()).isEqualTo(T0.plus(Duration.ofDays(7)));

        restarted.reset();
        assertThat(new DevClock(real, file).instant()).isEqualTo(T0);
    }
}
