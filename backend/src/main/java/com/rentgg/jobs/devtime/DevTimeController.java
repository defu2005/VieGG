package com.rentgg.jobs.devtime;

import com.rentgg.identity.application.AuthService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.CurrentUser;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * Dev profile only: move the server's business clock to test time-based flows (payment expiry, rental start/end,
 * reminders, seller hold, deposit release...). Every move answers with fresh tokens for the caller, because the
 * JWT/refresh lifetimes follow the same clock.
 */
@RestController
@RequestMapping("/api/v1/dev/time")
@Profile("dev")
public class DevTimeController {

    private final DevClock clock;
    private final DevTimeService time;
    private final AuthService auth;

    public DevTimeController(DevClock clock, DevTimeService time, AuthService auth) {
        this.clock = clock;
        this.time = time;
        this.auth = auth;
    }

    public record Status(Instant now, Instant realNow, long offsetSeconds, boolean frozen) {}

    public record Tokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken, Instant refreshTokenExpiresAt) {
        @Override
        public String toString() {
            return "Tokens[***]";
        }
    }

    public record TravelResponse(Status status, Instant from, int deadlineSteps, Tokens tokens) {}

    /** {@code duration}: ISO-8601 (PT90M, P2D, P1DT2H). Negative values move back. */
    public record AdvanceRequest(String duration, Boolean stepThroughDeadlines, Boolean runJobs) {}

    public record SetRequest(Instant at, Boolean stepThroughDeadlines, Boolean runJobs) {}

    public record FreezeRequest(boolean frozen) {}

    @GetMapping
    public Status status() {
        return currentStatus();
    }

    @GetMapping("/upcoming")
    public List<DevTimeService.Deadline> upcoming(@RequestParam(defaultValue = "false") boolean mine,
                                                 @RequestParam(defaultValue = "30") int limit) {
        UUID me = CurrentUser.id();
        int n = Math.max(1, Math.min(limit, 200));
        List<DevTimeService.Deadline> all = time.upcoming(mine ? 1000 : n);
        if (!mine) {
            return all;
        }
        return all.stream().filter(d -> me.equals(d.renterId()) || me.equals(d.ownerId())).limit(n).toList();
    }

    @PostMapping("/advance")
    public TravelResponse advance(@RequestBody AdvanceRequest req) {
        Duration d;
        try {
            d = Duration.parse(req.duration() == null ? "" : req.duration().trim().toUpperCase());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("VALIDATION_ERROR", "duration phải theo ISO-8601, ví dụ PT30M, PT2H, P1D.");
        }
        return travel(clock.instant().plus(d), req.stepThroughDeadlines(), req.runJobs());
    }

    @PostMapping("/set")
    public TravelResponse set(@RequestBody SetRequest req) {
        if (req.at() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Thiếu thời điểm 'at'.");
        }
        return travel(req.at(), req.stepThroughDeadlines(), req.runJobs());
    }

    @PostMapping("/freeze")
    public TravelResponse freeze(@RequestBody FreezeRequest req) {
        Instant from = clock.instant();
        clock.freeze(req.frozen());
        return new TravelResponse(currentStatus(), from, 0, tokens());
    }

    /** Back to real time (and unfrozen). Data created "in the future" stays as it is. */
    @PostMapping("/reset")
    public TravelResponse reset() {
        Instant from = clock.instant();
        clock.reset();
        return new TravelResponse(currentStatus(), from, 0, tokens());
    }

    private TravelResponse travel(Instant target, Boolean step, Boolean runJobs) {
        DevTimeService.TravelResult r = time.travelTo(target, step == null || step, runJobs == null || runJobs);
        return new TravelResponse(currentStatus(), r.from(), r.steps(), tokens());
    }

    private Status currentStatus() {
        return new Status(clock.instant(), clock.realNow(), clock.offset().toSeconds(), clock.isFrozen());
    }

    private Tokens tokens() {
        AuthService.Tokens t = auth.reissueAfterDevTimeTravel(CurrentUser.id());
        return new Tokens(t.accessToken(), t.accessTokenExpiresAt(), t.refreshToken(), t.refreshTokenExpiresAt());
    }
}
