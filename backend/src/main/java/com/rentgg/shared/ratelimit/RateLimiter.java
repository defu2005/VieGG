package com.rentgg.shared.ratelimit;

import com.rentgg.shared.error.ApiException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Bucket4j limiter (§12.5). With several instances the effective limit is
 * limit x instances, accepted for MVP; a Redis-backed store is Phase 2.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    /** Consumes one token or throws 429 RATE_LIMITED with Retry-After. */
    public void consume(String key, Limit... limits) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> build(limits));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retry = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Bạn thao tác quá nhiều lần, vui lòng thử lại sau.", Map.of("retryAfterSeconds", retry));
        }
    }

    private static Bucket build(Limit... limits) {
        var builder = Bucket.builder();
        for (Limit l : limits) {
            builder.addLimit(Bandwidth.builder().capacity(l.capacity()).refillIntervally(l.capacity(), l.period()).build());
        }
        return builder.build();
    }

    public record Limit(long capacity, Duration period) {
        public static Limit perMinute(long n) { return new Limit(n, Duration.ofMinutes(1)); }
        public static Limit perHour(long n) { return new Limit(n, Duration.ofHours(1)); }
    }
}
