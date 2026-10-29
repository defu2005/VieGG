package com.rentgg.jobs.devtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Properties;

/**
 * Dev-only clock that can be moved forward/back to test time-based flows by hand.
 * Business time = real time + offset (keeps ticking), or a fixed instant when frozen.
 * The state is saved to a file so a backend restart keeps the same "now".
 */
public class DevClock extends Clock {

    private static final Logger log = LoggerFactory.getLogger(DevClock.class);

    private final Clock real;
    private final Path stateFile;
    private volatile Duration offset = Duration.ZERO;
    private volatile Instant frozenAt;

    public DevClock(Clock real, Path stateFile) {
        this.real = real;
        this.stateFile = stateFile;
        load();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        DevClock self = this;
        return new Clock() {
            @Override public ZoneId getZone() { return zone; }
            @Override public Clock withZone(ZoneId z) { return self.withZone(z); }
            @Override public Instant instant() { return self.instant(); }
        };
    }

    @Override
    public Instant instant() {
        Instant f = frozenAt;
        return f != null ? f : real.instant().plus(offset);
    }

    public Instant realNow() {
        return real.instant();
    }

    public Duration offset() {
        return Duration.between(real.instant(), instant());
    }

    public boolean isFrozen() {
        return frozenAt != null;
    }

    /** Moves business time to {@code target}, keeping the frozen state. */
    public synchronized void set(Instant target) {
        if (frozenAt != null) {
            frozenAt = target;
        } else {
            offset = Duration.between(real.instant(), target);
        }
        save();
    }

    public synchronized void freeze(boolean freeze) {
        if (freeze && frozenAt == null) {
            frozenAt = instant();
        } else if (!freeze && frozenAt != null) {
            offset = Duration.between(real.instant(), frozenAt);
            frozenAt = null;
        }
        save();
    }

    public synchronized void reset() {
        offset = Duration.ZERO;
        frozenAt = null;
        save();
    }

    private void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(stateFile)) {
            Properties p = new Properties();
            p.load(r);
            offset = Duration.parse(p.getProperty("offset", "PT0S"));
            String frozen = p.getProperty("frozenAt", "");
            frozenAt = frozen.isBlank() ? null : Instant.parse(frozen);
            if (!offset.isZero() || frozenAt != null) {
                log.warn("DEV CLOCK: business time is shifted (offset {}, frozen at {}) - reset via POST /api/v1/dev/time/reset",
                        offset, frozenAt);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("DEV CLOCK: could not read {}, starting at real time", stateFile, e);
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("offset", offset.toString());
        p.setProperty("frozenAt", frozenAt == null ? "" : frozenAt.toString());
        try {
            Path parent = stateFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer w = Files.newBufferedWriter(stateFile)) {
                p.store(w, "VieGG dev clock (delete this file to return to real time)");
            }
        } catch (IOException e) {
            log.warn("DEV CLOCK: could not save {}", stateFile, e);
        }
    }
}
