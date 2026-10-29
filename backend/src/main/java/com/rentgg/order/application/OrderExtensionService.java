package com.rentgg.order.application;

import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.application.GameView;
import com.rentgg.config.RentGgProperties;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.identity.application.UserService;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.application.ListingView;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.listing.domain.PricingTier;
import com.rentgg.order.domain.Money;
import com.rentgg.order.domain.Order;
import com.rentgg.order.domain.OrderExtension;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.order.infrastructure.OrderExtensionRepository;
import com.rentgg.order.infrastructure.OrderRepository;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.error.DbErrors;
import com.rentgg.shared.outbox.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Extending a running rental (UC-17 P1, UC-37 step 2): one click from the order page, no new deposit, no new
 * approval and no new handover. Rules:
 * <ul>
 *   <li>only the renter, only while IN_RENTAL and before the scheduled end;</li>
 *   <li>listing still ACTIVE, game still allowed, account still IN_USE (not under security review), renter eligible;</li>
 *   <li>total rental time stays within the game's maximum duration;</li>
 *   <li>the extra time must be free: it is reserved at request time (exclusion constraint), so it can never take a
 *       slot sold to someone else, and released if the renter does not pay before the current block end;</li>
 *   <li>the new end time only applies once the payment succeeds.</li>
 * </ul>
 */
@Service
public class OrderExtensionService {

    private static final Logger log = LoggerFactory.getLogger(OrderExtensionService.class);

    /** Durations offered when the listing is priced per hour (packages otherwise). */
    static final List<Integer> HOURLY_EXTENSION_MINUTES = List.of(30, 60, 120, 180, 300);
    /** Payment needs at least this much time before the current block end, otherwise extending is refused. */
    static final Duration MIN_PAYMENT_WINDOW = Duration.ofMinutes(1);

    private final OrderRepository orders;
    private final OrderExtensionRepository extensions;
    private final ListingService listings;
    private final CatalogService catalog;
    private final GameAccountService gameAccounts;
    private final UserService users;
    private final PaymentService payments;
    private final LedgerService ledger;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final RentGgProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderExtensionService(OrderRepository orders, OrderExtensionRepository extensions, ListingService listings,
                                 CatalogService catalog, GameAccountService gameAccounts, UserService users,
                                 PaymentService payments, LedgerService ledger, OutboxService outbox,
                                 MeterRegistry meters, RentGgProperties props, TransactionTemplate tx, Clock clock) {
        this.orders = orders;
        this.extensions = extensions;
        this.listings = listings;
        this.catalog = catalog;
        this.gameAccounts = gameAccounts;
        this.users = users;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.meters = meters;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    // ================================================================= views

    public record ExtensionView(UUID id, UUID orderId, int durationMinutes, long rentAmount,
                                OrderExtension.Status status, Instant newScheduledEndAt, Instant newBlockEndAt,
                                Instant paymentExpiresAt, UUID paymentIntentId, Instant paidAt, Instant createdAt) {
        static ExtensionView of(OrderExtension e) {
            return new ExtensionView(e.getId(), e.getOrderId(), e.getDurationMinutes(), e.getRentAmount(),
                    e.getStatus(), e.getNewScheduledEndAt(), e.getNewBlockEndAt(), e.getPaymentExpiresAt(),
                    e.getPaymentIntentId(), e.getPaidAt(), e.getCreatedAt());
        }
    }

    /** One choice shown to the renter. {@code available = false} comes with the reason (e.g. next booking). */
    public record Option(int durationMinutes, long price, Instant newEndAt, boolean available, String unavailableReason) {}

    /**
     * Everything the order page needs to extend in one click: whether extending is possible (and why not),
     * the choices with their price and new end time, the next booking that limits them, and a pending extension.
     */
    public record Options(boolean allowed, String blockedCode, String blockedReason, Instant scheduledEndAt,
                          Instant nextBookingAt, int remainingMinutes, List<Option> options, ExtensionView pending,
                          List<ExtensionView> history) {}

    public record Checkout(ExtensionView extension, UUID intentId, long amount, String status, String checkoutUrl) {}

    // ================================================================= options

    /**
     * Not one transaction on purpose: the rule checks call other services whose refusals (exceptions) would mark a
     * surrounding transaction rollback-only, while here a refusal is just an answer.
     */
    public Options options(UUID callerId, UUID orderId) {
        Order o = tx.execute(s -> orders.findById(orderId).orElse(null));
        if (o == null || (!o.getRenterId().equals(callerId) && !o.getOwnerId().equals(callerId))) {
            throw ApiException.notFound("Đơn thuê");
        }
        List<ExtensionView> history = tx.execute(s -> extensions.findByOrderIdOrderByCreatedAt(orderId).stream()
                .map(ExtensionView::of).toList());
        ExtensionView pending = history.stream().filter(e -> e.status() == OrderExtension.Status.PENDING_PAYMENT)
                .findFirst().orElse(null);
        Instant now = clock.instant();
        if (!o.getRenterId().equals(callerId)) {
            return new Options(false, "NOT_RENTER", "Chỉ người thuê được gia hạn.", o.getScheduledEndAt(), null, 0,
                    List.of(), pending, history);
        }
        Context c;
        try {
            c = context(o, now);
        } catch (ApiException e) {
            return new Options(false, e.code(), e.getMessage(), o.getScheduledEndAt(), null, 0, List.of(), pending, history);
        }
        List<Option> opts = new ArrayList<>();
        for (PricingTier t : c.choices()) {
            Instant newEnd = o.getScheduledEndAt().plus(Duration.ofMinutes(t.durationMinutes()));
            Instant newBlockEnd = newEnd.plus(Duration.ofMinutes(c.bufferMinutes()));
            String reason = null;
            if (t.durationMinutes() > c.remainingMinutes()) {
                reason = "Vượt thời lượng thuê tối đa của game.";
            } else if (c.nextBookingAt() != null && newBlockEnd.isAfter(c.nextBookingAt())) {
                reason = c.nextIsOwnerBusy() ? "Trùng giờ bận của chủ account." : "Trùng lịch người thuê sau.";
            }
            opts.add(new Option(t.durationMinutes(), t.price(), newEnd, reason == null, reason));
        }
        return new Options(true, null, null, o.getScheduledEndAt(), c.nextBookingAt(), c.remainingMinutes(), opts,
                pending, history);
    }

    // ================================================================= request + pay

    /**
     * Reserves the extra time and opens the payment in one call. Replays with the same Idempotency-Key return the
     * same extension and checkout.
     */
    public Checkout request(UUID renterId, UUID orderId, int durationMinutes, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key.");
        }
        ExtensionView ext = tx.execute(s -> reserve(renterId, orderId, durationMinutes, idempotencyKey));
        return checkout(renterId, ext);
    }

    /** Re-opens the checkout of a pending extension (e.g. the renter closed the payment page). */
    public Checkout pay(UUID renterId, UUID orderId, UUID extensionId) {
        ExtensionView ext = tx.execute(s -> {
            OrderExtension e = ownPending(renterId, orderId, extensionId);
            if (!e.getPaymentExpiresAt().isAfter(clock.instant())) {
                throw ApiException.invalidState("Yêu cầu gia hạn đã hết thời gian thanh toán.");
            }
            return ExtensionView.of(e);
        });
        return checkout(renterId, ext);
    }

    /**
     * Pays the extension from the renter's wallet with the order locked. Not enough balance → 422
     * INSUFFICIENT_WALLET_BALANCE and the extension stays reserved until its payment deadline, so the renter can
     * top up and pay it again.
     */
    private Checkout checkout(UUID renterId, ExtensionView ext) {
        String key = OrderExtension.PAYMENT_KEY_PREFIX + ext.id();
        return tx.execute(s -> {
            orders.findForUpdate(ext.orderId()).orElseThrow();
            OrderExtension e = extensions.findForUpdate(ext.id()).orElseThrow();
            PaymentService.IntentView intent = payments.findIntent(ext.orderId(), key).orElse(null);
            if (intent == null) {
                if (!e.isPending() || !e.getPaymentExpiresAt().isAfter(clock.instant())) {
                    throw ApiException.invalidState("Yêu cầu gia hạn không còn chờ thanh toán.");
                }
                intent = payments.payFromWallet(ext.orderId(), renterId, e.getRentAmount(), key);
            }
            if (e.getPaymentIntentId() == null) {
                e.attachPaymentIntent(intent.id(), clock.instant());
            }
            return new Checkout(ExtensionView.of(e), intent.id(), intent.amount(), intent.status(), intent.checkoutUrl());
        });
    }

    private ExtensionView reserve(UUID renterId, UUID orderId, int durationMinutes, String idempotencyKey) {
        Order o = orders.findForUpdate(orderId).filter(x -> x.getRenterId().equals(renterId))
                .orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        var replay = extensions.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
        if (replay.isPresent()) {
            if (replay.get().getDurationMinutes() != durationMinutes) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key đã được dùng cho yêu cầu khác.");
            }
            return ExtensionView.of(replay.get());
        }
        if (extensions.findPendingForUpdate(orderId).isPresent()) {
            throw ApiException.conflict("EXTENSION_PENDING",
                    "Bạn đang có một yêu cầu gia hạn chờ thanh toán. Hãy thanh toán hoặc hủy yêu cầu đó trước.");
        }
        Instant now = clock.instant();
        Context c = context(o, now);
        PricingTier choice = c.price(durationMinutes);
        if (durationMinutes > c.remainingMinutes()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DURATION_OUT_OF_RANGE",
                    "Gia hạn vượt thời lượng thuê tối đa của game.", Map.of("remainingMinutes", c.remainingMinutes()));
        }
        Instant newEnd = o.getScheduledEndAt().plus(Duration.ofMinutes(durationMinutes));
        Instant newBlockEnd = newEnd.plus(Duration.ofMinutes(c.bufferMinutes()));
        if (c.nextBookingAt() != null && newBlockEnd.isAfter(c.nextBookingAt())) {
            throw slotUnavailable(o, c.nextBookingAt());
        }
        long fee = Money.fee(choice.price(), o.policyInt("platformFeeBps"));
        OrderExtension e = new OrderExtension(orderId, renterId, durationMinutes, choice.price(), fee, newEnd,
                newBlockEnd, c.paymentDeadline(), idempotencyKey, now);
        o.holdForExtension(newBlockEnd, now);
        try {
            // The exclusion constraint is the final arbiter against a booking made at the same moment.
            orders.saveAndFlush(o);
        } catch (DataIntegrityViolationException ex) {
            if (DbErrors.isSqlState(ex, DbErrors.EXCLUSION_VIOLATION, "ex_orders_no_overlap")) {
                meters.counter("rentgg_slot_conflicts_total").increment();
                throw slotUnavailable(o, null);
            }
            throw ex;
        }
        extensions.saveAndFlush(e);
        outbox.record("Order", orderId, "OrderExtensionRequested", Map.of("orderId", orderId, "extensionId", e.getId(),
                "durationMinutes", durationMinutes, "rentAmount", choice.price(), "newScheduledEndAt", newEnd,
                "paymentExpiresAt", e.getPaymentExpiresAt()));
        meters.counter("rentgg_order_extensions_total", "result", "requested").increment();
        return ExtensionView.of(e);
    }

    @Transactional
    public ExtensionView cancel(UUID renterId, UUID orderId, UUID extensionId) {
        Order o = orders.findForUpdate(orderId).filter(x -> x.getRenterId().equals(renterId))
                .orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        OrderExtension e = extensions.findForUpdate(extensionId).filter(x -> x.getOrderId().equals(orderId))
                .orElseThrow(() -> ApiException.notFound("Yêu cầu gia hạn"));
        if (e.getStatus() == OrderExtension.Status.CANCELLED) {
            return ExtensionView.of(e);
        }
        if (!e.isPending()) {
            throw ApiException.invalidState("Yêu cầu gia hạn không còn chờ thanh toán.");
        }
        close(o, e, OrderExtension.Status.CANCELLED);
        return ExtensionView.of(e);
    }

    // ================================================================= payment captured (webhook tx)

    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentCaptured(UUID intentId, UUID orderId, UUID extensionId, long amount) {
        Order o = orders.findForUpdate(orderId).orElseThrow();
        OrderExtension e = extensions.findForUpdate(extensionId).orElseThrow();
        if (!e.getOrderId().equals(orderId) || amount != e.getRentAmount()) {
            throw new IllegalStateException("Captured amount does not match extension " + extensionId);
        }
        ledger.post(TxType.PAYMENT_CAPTURED, orderId, "intent:" + intentId, "PAYMENT_CAPTURED:" + intentId, null,
                List.of(Posting.debit(AccountCode.PSP_CLEARING, amount), Posting.credit(AccountCode.RENT_ESCROW, amount)));
        Instant now = clock.instant();
        boolean inTime = e.isPending() && o.getStatus() == OrderStatus.IN_RENTAL
                && !now.isAfter(e.getPaymentExpiresAt()) && e.getNewBlockEndAt().equals(o.getExtensionHoldUntil());
        if (inTime) {
            o.applyExtension(e, now);
            e.markPaid(now);
            if (e.getPaymentIntentId() == null) {
                e.attachPaymentIntent(intentId, now);
            }
            outbox.record("Order", orderId, "OrderExtended", Map.of("orderId", orderId, "extensionId", extensionId,
                    "durationMinutes", e.getDurationMinutes(), "rentAmount", e.getRentAmount(),
                    "newScheduledEndAt", o.getScheduledEndAt(), "blockEndAt", o.getBlockEndAt(),
                    "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
            meters.counter("rentgg_order_extensions_total", "result", "paid").increment();
            return;
        }
        // Paid too late (rental over, extension expired/cancelled): the time was never granted → refund 100%.
        if (e.isPending()) {
            close(o, e, OrderExtension.Status.EXPIRED);
        }
        ledger.post(TxType.ORDER_CANCELLED_REFUND, orderId, "intent:" + intentId, "LATE_EXTENSION_REFUND:" + intentId, null,
                List.of(Posting.debit(AccountCode.RENT_ESCROW, amount), Posting.credit(AccountCode.REFUND_PAYABLE, amount)));
        payments.requestRefund(orderId, intentId, "RENT", amount, "LATE_EXTENSION_PAYMENT");
        Map<String, Object> p = new HashMap<>();
        p.put("orderId", orderId);
        p.put("extensionId", extensionId);
        p.put("refund", amount);
        p.put("renterId", o.getRenterId());
        outbox.record("Order", orderId, "OrderExtensionFailed", p);
        meters.counter("rentgg_order_extensions_total", "result", "late_payment").increment();
    }

    // ================================================================= jobs

    /** Releases the reserved time of extensions not paid before their deadline. */
    public int expireUnpaid(int batch) {
        int n = 0;
        for (UUID orderId : extensions.findOrdersWithExpiredExtension(clock.instant(), batch)) {
            try {
                Boolean done = tx.execute(s -> orders.lockForJob(orderId).map(this::expireIfDue).orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    n++;
                }
            } catch (RuntimeException ex) {
                log.error("Extension expiry failed for order {}", orderId, ex);
            }
        }
        return n;
    }

    private boolean expireIfDue(Order o) {
        OrderExtension e = extensions.findPendingForUpdate(o.getId()).orElse(null);
        if (e == null || e.getPaymentExpiresAt().isAfter(clock.instant())) {
            return false;
        }
        close(o, e, OrderExtension.Status.EXPIRED);
        return true;
    }

    /** Called when the order ends: a pending extension can no longer be granted. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closePendingOnEnd(Order o) {
        extensions.findPendingForUpdate(o.getId()).ifPresent(e -> close(o, e, OrderExtension.Status.EXPIRED));
    }

    private void close(Order o, OrderExtension e, OrderExtension.Status terminal) {
        Instant now = clock.instant();
        e.close(terminal, now);
        o.releaseExtensionHold(now);
        payments.expireOpenIntents(o.getId());
        if (terminal == OrderExtension.Status.EXPIRED) {
            outbox.record("Order", o.getId(), "OrderExtensionExpired", Map.of("orderId", o.getId(),
                    "extensionId", e.getId(), "renterId", o.getRenterId()));
        }
        meters.counter("rentgg_order_extensions_total", "result", terminal.name().toLowerCase()).increment();
    }

    // ================================================================= rules

    /** What limits an extension of this order right now; throws when extending is not possible at all. */
    private record Context(List<PricingTier> choices, ListingView listing, int bufferMinutes, int remainingMinutes,
                           Instant nextBookingAt, Instant paymentDeadline, boolean nextIsOwnerBusy) {

        PricingTier price(int minutes) {
            return choices.stream().filter(t -> t.durationMinutes() == minutes).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DURATION_NOT_OFFERED",
                            "Không có lựa chọn gia hạn với thời lượng này.",
                            Map.of("durations", choices.stream().map(PricingTier::durationMinutes).toList())));
        }
    }

    private Context context(Order o, Instant now) {
        if (o.getStatus() != OrderStatus.IN_RENTAL) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE", "Chỉ gia hạn được khi đơn đang trong thời gian thuê.");
        }
        if (!now.isBefore(o.getScheduledEndAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "RENTAL_TIME_OVER", "Đã hết giờ thuê, không thể gia hạn.", null);
        }
        Instant deadline = now.plus(Duration.ofMinutes(props.order().paymentHoldMinutes()));
        if (deadline.isAfter(o.getBlockEndAt())) {
            deadline = o.getBlockEndAt();
        }
        if (Duration.between(now, deadline).compareTo(MIN_PAYMENT_WINDOW) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "RENTAL_TIME_OVER", "Sắp hết giờ, không còn đủ thời gian để gia hạn.", null);
        }
        ListingView listing = listings.get(o.getListingId());
        if (listing.status() != ListingStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "LISTING_NOT_ACTIVE",
                    "Chủ account đã tạm dừng nhận đơn nên không gia hạn được.", null);
        }
        GameView game = catalog.get(o.getGameId());
        if (!game.allowed()) {
            throw new ApiException(HttpStatus.CONFLICT, "GAME_NOT_ALLOWED", "Game đang tạm ngừng cho thuê.", null);
        }
        if (gameAccounts.state(o.getGameAccountId()) != SecurityState.IN_USE) {
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_AVAILABLE",
                    "Account đang được kiểm tra bảo mật, không thể gia hạn.", null);
        }
        users.requireEligible(o.getRenterId(), game.minAge(), true);
        int usedMinutes = (int) Duration.between(o.getStartAt(), o.getScheduledEndAt()).toMinutes();
        int remaining = game.maxDurationMinutes() - usedMinutes;
        if (remaining <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "MAX_DURATION_REACHED",
                    "Đơn đã đạt thời lượng thuê tối đa của game. Bạn có thể đặt đơn mới sau khi đơn này kết thúc.",
                    Map.of("maxDurationMinutes", game.maxDurationMinutes()));
        }
        List<PricingTier> choices = listing.pricingTiers().isEmpty()
                ? HOURLY_EXTENSION_MINUTES.stream()
                    .map(m -> new PricingTier(m, Money.rent(listing.pricePerHour(), m)))
                    .filter(t -> t.price() > 0).toList()
                : listing.pricingTiers();
        int buffer = o.endMode().bufferMinutes(o.policyInt("graceMinutes"));
        Instant next = extensions.findNextBookingStart(o.getGameAccountId(), o.getId(), o.getStartAt(), OrderStatus.ACTIVE);
        // Owner busy time limits an extension just like the next booking does.
        Instant busy = listings.nextOwnerBusyStart(o.getGameAccountId(), o.getStartAt());
        boolean ownerBusyFirst = busy != null && (next == null || busy.isBefore(next));
        return new Context(choices, listing, buffer, remaining, ownerBusyFirst ? busy : next, deadline, ownerBusyFirst);
    }

    private ApiException slotUnavailable(Order o, Instant nextBookingAt) {
        Map<String, Object> d = new HashMap<>();
        d.put("gameAccountId", o.getGameAccountId());
        if (nextBookingAt != null) {
            d.put("nextBookingAt", nextBookingAt.toString());
        }
        return new ApiException(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE",
                "Khung giờ sau đơn của bạn đã có người đặt, hãy chọn thời lượng ngắn hơn.", d);
    }

    private OrderExtension ownPending(UUID renterId, UUID orderId, UUID extensionId) {
        orders.findById(orderId).filter(x -> x.getRenterId().equals(renterId))
                .orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        OrderExtension e = extensions.findById(extensionId).filter(x -> x.getOrderId().equals(orderId))
                .orElseThrow(() -> ApiException.notFound("Yêu cầu gia hạn"));
        if (!e.isPending()) {
            throw ApiException.invalidState("Yêu cầu gia hạn không còn chờ thanh toán.");
        }
        return e;
    }
}
