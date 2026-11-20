package com.rentgg.identity.application;

import com.rentgg.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progressive wait after wrong passwords (System Design §12.5, "sai liên tiếp → chờ lũy tiến"). Keyed like the login
 * limiter (IP and lower-cased email). Only wrong passwords count: the first five are free, then the wait is 30 s
 * doubling up to 15 minutes. A state idle for an hour is forgotten. In memory and per instance: it is lost on restart,
 * like the Bucket4j limiter. Constants are fixed in code, not configuration.
 */
@Component
public class LoginBackoff {

    static final int FREE_FAILURES = 5;
    static final Duration BASE_WAIT = Duration.ofSeconds(30);
    static final Duration MAX_WAIT = Duration.ofMinutes(15);
    static final Duration FORGET_AFTER = Duration.ofHours(1);
    static final int MAX_ENTRIES = 50_000;

    private record State(int failures, Instant blockedUntil, Instant lastSeen) {}

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginBackoff(Clock clock) {
        this.clock = clock;
    }

    /** Throws 429 RATE_LIMITED (same shape as the login limiter) while the key is waiting. Runs before the password check. */
    public void checkBeforePassword(String key) {
        Instant now = clock.instant();
        State s = states.get(key);
        if (s == null || isForgotten(s, now) || !now.isBefore(s.blockedUntil())) {
            return;
        }
        long retry = Math.max(1, Duration.between(now, s.blockedUntil()).toSeconds());
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Bạn thao tác quá nhiều lần, vui lòng thử lại sau.", Map.of("retryAfterSeconds", retry));
    }

    /** A wrong password. Each call is atomic per key. */
    public void recordWrongPassword(String key) {
        Instant now = clock.instant();
        if (states.size() >= MAX_ENTRIES) {
            states.entrySet().removeIf(e -> isForgotten(e.getValue(), now));
            if (states.size() >= MAX_ENTRIES) {
                states.clear();
            }
        }
        states.compute(key, (k, old) -> {
            int failures = old == null || isForgotten(old, now) ? 1 : old.failures() + 1;
            Instant blockedUntil = failures <= FREE_FAILURES ? now : now.plus(waitAfter(failures));
            return new State(failures, blockedUntil, now);
        });
    }

    /** Any issued session (a correct password) forgets the key. */
    public void reset(String key) {
        states.remove(key);
    }

    /** The wait after the n-th wrong password (n > FREE_FAILURES): 30 s, 60 s, ... up to the 15-minute cap. */
    static Duration waitAfter(int failures) {
        int doublings = Math.min(failures - FREE_FAILURES - 1, 30);
        Duration wait = BASE_WAIT.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    private static boolean isForgotten(State s, Instant now) {
        return Duration.between(s.lastSeen(), now).compareTo(FORGET_AFTER) > 0;
    }
}
