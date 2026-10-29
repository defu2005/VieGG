package com.rentgg.order.api;

import com.rentgg.config.RentGgProperties;
import com.rentgg.deposit.application.DepositService;
import com.rentgg.deposit.application.DepositView;
import com.rentgg.order.application.HandoverService;
import com.rentgg.order.application.OrderExtensionService;
import com.rentgg.order.application.OrderService;
import com.rentgg.order.application.OrderView;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.ratelimit.RateLimiter;
import com.rentgg.shared.ratelimit.RateLimiter.Limit;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.web.PageResponse;
import com.rentgg.shared.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orders;
    private final OrderExtensionService extensions;
    private final HandoverService handover;
    private final DepositService deposits;
    private final PaymentService payments;
    private final RateLimiter rateLimiter;
    private final RentGgProperties props;

    public OrderController(OrderService orders, OrderExtensionService extensions, HandoverService handover,
                           DepositService deposits, PaymentService payments, RateLimiter rateLimiter,
                           RentGgProperties props) {
        this.orders = orders;
        this.extensions = extensions;
        this.handover = handover;
        this.deposits = deposits;
        this.payments = payments;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('order:place')")
    public ResponseEntity<OrderView> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody CreateOrderRequest req) {
        UUID renter = CurrentUser.id();
        rateLimiter.consume("orders:" + renter, Limit.perHour(props.rateLimit().ordersPerHour()));
        OrderService.CreateResult r = orders.create(renter, req.listingId(), req.startAt(), req.durationMinutes(), idempotencyKey);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(r.order());
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('order:place')")
    public PayResponse pay(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        PaymentService.IntentView intent = orders.pay(CurrentUser.id(), id, idempotencyKey);
        return new PayResponse(intent.id(), intent.amount(), intent.status(), intent.checkoutUrl());
    }

    @GetMapping("/{id}")
    public OrderDetail get(@PathVariable UUID id) {
        OrderView o = orders.getForCaller(CurrentUser.id(), id);
        return new OrderDetail(o, deposits.getByOrder(id), payments.refundsForOrder(id));
    }

    @GetMapping
    public PageResponse<OrderView> list(@RequestParam(defaultValue = "RENTER") String role,
                                        @RequestParam(required = false) OrderStatus status,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String sort) {
        var pageable = Paging.of(page, size, sort, Set.of("createdAt", "startAt"), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(orders.list(CurrentUser.id(), role, status, pageable), x -> x);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('order:manage_own')")
    public OrderView approve(@PathVariable UUID id) {
        return orders.approve(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('order:manage_own')")
    public OrderView reject(@PathVariable UUID id) {
        return orders.reject(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('order:manage_own')")
    public OrderView cancel(@PathVariable UUID id) {
        return orders.cancel(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- extension (UC-17 P1)

    /** Extension choices for the order page (renter), plus pending/past extensions (renter and owner). */
    @GetMapping("/{id}/extensions")
    public OrderExtensionService.Options extensionOptions(@PathVariable UUID id) {
        return extensions.options(CurrentUser.id(), id);
    }

    /** Reserves the extra time and opens its payment in one call. */
    @PostMapping("/{id}/extensions")
    @PreAuthorize("hasAuthority('order:place')")
    public OrderExtensionService.Checkout extend(@PathVariable UUID id,
                                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                 @Valid @RequestBody ExtendRequest req) {
        UUID renter = CurrentUser.id();
        rateLimiter.consume("orders:" + renter, Limit.perHour(props.rateLimit().ordersPerHour()));
        return extensions.request(renter, id, req.durationMinutes(), idempotencyKey);
    }

    @PostMapping("/{id}/extensions/{extensionId}/pay")
    @PreAuthorize("hasAuthority('order:place')")
    public OrderExtensionService.Checkout payExtension(@PathVariable UUID id, @PathVariable UUID extensionId) {
        return extensions.pay(CurrentUser.id(), id, extensionId);
    }

    @PostMapping("/{id}/extensions/{extensionId}/cancel")
    @PreAuthorize("hasAuthority('order:place')")
    public OrderExtensionService.ExtensionView cancelExtension(@PathVariable UUID id, @PathVariable UUID extensionId) {
        return extensions.cancel(CurrentUser.id(), id, extensionId);
    }

    /** §12.4: renter only, IN_RENTAL, inside [start_at, block_end_at], rate limited, audited, no-store. */
    @GetMapping("/{id}/credentials")
    @PreAuthorize("hasAuthority('credential:view_own')")
    public ResponseEntity<HandoverService.CredentialsResponse> credentials(@PathVariable UUID id) {
        HandoverService.CredentialsResponse body = handover.viewCredentials(CurrentUser.id(), id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(body);
    }

    @PostMapping("/{id}/handover/confirm")
    @PreAuthorize("hasAuthority('order:manage_own')")
    public Map<String, Instant> confirmHandover(@PathVariable UUID id) {
        return Map.of("handoverConfirmedAt", handover.confirmHandover(CurrentUser.id(), id));
    }

    @PostMapping("/{id}/otp-request")
    @PreAuthorize("hasAuthority('order:manage_own')")
    public Map<String, String> requestOtp(@PathVariable UUID id) {
        handover.requestOtp(CurrentUser.id(), id);
        return Map.of("status", "REQUESTED");
    }

    @PostMapping("/{id}/otp-note")
    @PreAuthorize("hasAuthority('order:manage_own')")
    public Map<String, String> postOtpNote(@PathVariable UUID id, @Valid @RequestBody OtpNoteRequest req) {
        handover.postOtpNote(CurrentUser.id(), id, req.note());
        return Map.of("status", "POSTED");
    }

    /** {@code startAt} omitted = start as soon as the order is paid (and approved, if the listing needs approval). */
    public record CreateOrderRequest(@NotNull UUID listingId, Instant startAt, @Positive int durationMinutes) {}

    public record ExtendRequest(@Positive int durationMinutes) {}

    public record PayResponse(UUID intentId, long amount, String status, String checkoutUrl) {}

    public record OtpNoteRequest(@NotBlank @Size(max = 64) String note) {
        @Override
        public String toString() {
            return "OtpNoteRequest[note=***]";
        }
    }

    public record OrderDetail(OrderView order, DepositView deposit, List<PaymentService.RefundView> refunds) {}
}
