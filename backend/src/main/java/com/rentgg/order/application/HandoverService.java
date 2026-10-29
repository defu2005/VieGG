package com.rentgg.order.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.catalog.domain.HandoverMode;
import com.rentgg.config.RentGgProperties;
import com.rentgg.gameaccount.application.DecryptedCredentials;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.order.domain.Order;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.order.infrastructure.OrderRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.ratelimit.RateLimiter;
import com.rentgg.shared.ratelimit.RateLimiter.Limit;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Direct credential handover (UC-08, §8.3, §12.4) — no client/launcher. */
@Service
public class HandoverService {

    private final OrderRepository orders;
    private final GameAccountService gameAccounts;
    private final UserService users;
    private final RateLimiter rateLimiter;
    private final AuditService audit;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final RentGgProperties props;
    private final Clock clock;

    public HandoverService(OrderRepository orders, GameAccountService gameAccounts, UserService users,
                           RateLimiter rateLimiter, AuditService audit, OutboxService outbox, MeterRegistry meters,
                           RentGgProperties props, Clock clock) {
        this.orders = orders;
        this.gameAccounts = gameAccounts;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
        this.outbox = outbox;
        this.meters = meters;
        this.props = props;
        this.clock = clock;
    }

    public record CredentialsResponse(String username, String password, int credentialVersion,
                                      Instant complaintWindowEndsAt, Instant availableUntil, String otpNote) {
        @Override
        public String toString() {
            return "CredentialsResponse[username=***, password=***, version=" + credentialVersion + "]";
        }
    }

    /**
     * Returns credentials only when ALL conditions of §12.4 hold. Every view is audited (without the password);
     * denied attempts are audited in a separate transaction.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public CredentialsResponse viewCredentials(UUID callerId, UUID orderId) {
        Order o = orders.findForUpdate(orderId).orElse(null);
        if (o == null || !o.getRenterId().equals(callerId)) {
            audit.recordIndependent(callerId, "CREDENTIAL_VIEW_DENIED", "Order", orderId, Map.of("reason", "NOT_RENTER"), null);
            throw ApiException.notFound("Đơn thuê");
        }
        if (users.get(callerId).status() == UserStatus.SUSPENDED) {
            audit.recordIndependent(callerId, "CREDENTIAL_VIEW_DENIED", "Order", orderId, Map.of("reason", "USER_SUSPENDED"), null);
            throw ApiException.forbidden("USER_SUSPENDED", "Tài khoản của bạn đang bị đình chỉ.");
        }
        Instant now = clock.instant();
        if (o.getStatus() != OrderStatus.IN_RENTAL || now.isBefore(o.getStartAt()) || now.isAfter(o.getBlockEndAt())
                || gameAccounts.state(o.getGameAccountId()) != SecurityState.IN_USE) {
            audit.recordIndependent(callerId, "CREDENTIAL_VIEW_DENIED", "Order", orderId,
                    Map.of("reason", "WINDOW_CLOSED", "status", o.getStatus().name()), null);
            throw ApiException.unprocessable("CREDENTIAL_WINDOW_CLOSED", "Chỉ xem được thông tin đăng nhập trong thời gian thuê.");
        }
        try {
            rateLimiter.consume("credentials:" + orderId, Limit.perHour(props.credentials().rateLimitPerHour()));
        } catch (ApiException e) {
            audit.recordIndependent(callerId, "CREDENTIAL_VIEW_RATE_LIMITED", "Order", orderId, null, null);
            throw e;
        }
        DecryptedCredentials creds = gameAccounts.decryptActive(o.getGameAccountId());
        boolean first = o.recordFirstView(now);
        audit.record(callerId, "CREDENTIAL_VIEWED", "Order", orderId, null,
                Map.of("credentialVersion", creds.version(), "firstView", first), null);
        if (first) {
            outbox.record("Order", orderId, "CredentialViewed", Map.of("orderId", orderId, "renterId", callerId,
                    "ownerId", o.getOwnerId(), "viewedAt", now, "firstView", true));
        }
        meters.counter("rentgg_credential_views_total", "first_view", String.valueOf(first)).increment();
        return new CredentialsResponse(creds.username(), creds.password(), creds.version(), o.complaintWindowEndsAt(),
                o.getBlockEndAt(), o.activeOtpNote(now));
    }

    /** Renter confirms login success: closes the complaint window early. */
    @Transactional
    public Instant confirmHandover(UUID callerId, UUID orderId) {
        Order o = lockAsRenter(callerId, orderId);
        if (o.getStatus() != OrderStatus.IN_RENTAL) {
            throw ApiException.invalidState("Đơn không trong thời gian thuê.");
        }
        if (o.confirmHandover(clock.instant())) {
            outbox.record("Order", orderId, "HandoverConfirmed", Map.of("orderId", orderId,
                    "confirmedAt", o.getHandoverConfirmedAt(), "ownerId", o.getOwnerId()));
        }
        return o.getHandoverConfirmedAt();
    }

    /** OWNER_ASSISTED_OTP: renter asks the owner for a code. The platform never generates or reads OTPs. */
    @Transactional
    public void requestOtp(UUID callerId, UUID orderId) {
        Order o = lockAsRenter(callerId, orderId);
        if (!HandoverMode.OWNER_ASSISTED_OTP.name().equals(o.policyString("handoverMode"))) {
            throw ApiException.unprocessable("OTP_NOT_APPLICABLE", "Game này không dùng OTP do chủ account hỗ trợ.");
        }
        if (o.getStatus() != OrderStatus.IN_RENTAL) {
            throw ApiException.invalidState("Đơn không trong thời gian thuê.");
        }
        rateLimiter.consume("otp:" + orderId, Limit.perHour(props.rateLimit().otpPerHour()));
        outbox.record("Order", orderId, "OtpRequested", Map.of("orderId", orderId, "ownerId", o.getOwnerId(),
                "renterId", callerId, "requestedAt", clock.instant()));
    }

    /** Owner relays the code through the order (MVP channel). Kept only for a few minutes, cleared at end. */
    @Transactional
    public void postOtpNote(UUID callerId, UUID orderId, String note) {
        Order o = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        if (!o.getOwnerId().equals(callerId)) {
            throw ApiException.notFound("Đơn thuê");
        }
        if (o.getStatus() != OrderStatus.IN_RENTAL) {
            throw ApiException.invalidState("Đơn không trong thời gian thuê.");
        }
        Instant now = clock.instant();
        o.setOtpNote(note, now.plus(Duration.ofMinutes(props.order().otpNoteTtlMinutes())), now);
        audit.record(callerId, "OTP_NOTE_POSTED", "Order", orderId, null, null, null);
    }

    private Order lockAsRenter(UUID callerId, UUID orderId) {
        Order o = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        if (!o.getRenterId().equals(callerId)) {
            throw ApiException.notFound("Đơn thuê");
        }
        return o;
    }
}
