package com.rentgg.order.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.application.GameRiskView;
import com.rentgg.catalog.application.GameView;
import com.rentgg.catalog.domain.RiskSide;
import com.rentgg.config.RentGgProperties;
import com.rentgg.deposit.application.DepositService;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.identity.application.AbuseSignalService;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.application.UserView;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.application.ListingView;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.order.domain.CancelReason;
import com.rentgg.order.domain.Money;
import com.rentgg.order.domain.Order;
import com.rentgg.order.domain.OrderStatus;
import com.rentgg.order.infrastructure.OrderRepository;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.error.DbErrors;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.rentgg.order.domain.OrderStatus.*;

/** Order lifecycle (§6.1, §8.1, §8.2, §8.8). Each public write is one transaction. */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final ListingService listings;
    private final CatalogService catalog;
    private final GameAccountService gameAccounts;
    private final UserService users;
    private final AbuseSignalService signals;
    private final DepositService deposits;
    private final PaymentService payments;
    private final LedgerService ledger;
    private final OutboxService outbox;
    private final AuditService audit;
    private final ObjectProvider<OrderLifecycleListener> lifecycleListeners;
    private final OrderExtensionService extensions;
    private final MeterRegistry meters;
    private final RentGgProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(OrderRepository orders, ListingService listings, CatalogService catalog,
                        GameAccountService gameAccounts, UserService users, AbuseSignalService signals,
                        DepositService deposits, PaymentService payments, LedgerService ledger, OutboxService outbox,
                        AuditService audit, ObjectProvider<OrderLifecycleListener> lifecycleListeners,
                        OrderExtensionService extensions, MeterRegistry meters,
                        RentGgProperties props, TransactionTemplate tx, Clock clock) {
        this.orders = orders;
        this.extensions = extensions;
        this.listings = listings;
        this.catalog = catalog;
        this.gameAccounts = gameAccounts;
        this.users = users;
        this.signals = signals;
        this.deposits = deposits;
        this.payments = payments;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.lifecycleListeners = lifecycleListeners;
        this.meters = meters;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    // ================================================================= create (UC-06)

    public record CreateResult(OrderView order, boolean created) {}

    /**
     * The game's risks with the listing's amount for each, frozen into the order. {@code amount}: deposit share for an
     * OWNER risk (null for listings made before risks existed: the whole deposit covers it), compensation for a
     * RENTER risk (null = rent refund only). {@code covered} false: the owner chose no deposit for this OWNER risk,
     * so they cannot claim the deposit for it.
     */
    private static List<Map<String, Object>> riskSnapshot(GameView game, ListingView listing) {
        List<Map<String, Object>> list = new ArrayList<>();
        // Listings made before risks existed have no terms: the whole deposit covers every owner risk.
        boolean legacy = listing.riskTerms() == null || listing.riskTerms().isEmpty();
        for (GameRiskView r : game.risks()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("riskId", r.id().toString());
            m.put("side", r.side().name());
            m.put("name", r.name());
            m.put("description", r.description());
            m.put("amount", listing.riskAmount(r.id()));
            m.put("complaintWindowMinutes", r.complaintWindowMinutes());
            m.put("stackable", r.stackable());
            m.put("ownerDefined", r.ownerDefined());
            m.put("covered", r.side() != RiskSide.OWNER || legacy || listing.riskAmount(r.id()) != null);
            list.add(m);
        }
        return list;
    }

    /**
     * Creates an order. {@code startAt == null} means "start as soon as it is paid": the rental begins at payment
     * (or at owner approval when the listing does not auto-accept), so the renter can play right away.
     */
    @Transactional
    public CreateResult create(UUID renterId, UUID listingId, Instant startAt, int durationMinutes, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key.");
        }
        boolean startOnPayment = startAt == null;
        var existing = orders.findByRenterIdAndIdempotencyKey(renterId, idempotencyKey);
        if (existing.isPresent()) {
            Order o = existing.get();
            int existingDuration = ((Number) o.getPolicySnapshot().get("durationMinutes")).intValue();
            boolean sameStart = startOnPayment ? o.startsOnPayment() : (!o.startsOnPayment() && o.getStartAt().equals(startAt));
            if (!o.getListingId().equals(listingId) || !sameStart || existingDuration != durationMinutes) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key đã được dùng cho yêu cầu khác.");
            }
            return new CreateResult(OrderView.of(o), false);
        }

        Instant now = clock.instant();
        if (startOnPayment) {
            startAt = now;
        }
        ListingView listing = listings.get(listingId);
        if (listing.status() != ListingStatus.ACTIVE) {
            throw ApiException.notFound("Listing");
        }
        if (listing.ownerId().equals(renterId)) {
            throw ApiException.unprocessable("CANNOT_RENT_OWN_LISTING", "Bạn không thể thuê account của chính mình.");
        }
        GameView game = catalog.requireAllowed(listing.gameId());
        UserView renter = users.requireEligible(renterId, game.minAge(), true);
        if (durationMinutes < game.minDurationMinutes() || durationMinutes > game.maxDurationMinutes()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DURATION_OUT_OF_RANGE",
                    "Thời lượng thuê không hợp lệ.", Map.of("min", game.minDurationMinutes(), "max", game.maxDurationMinutes()));
        }
        if (startAt.isBefore(now)) {
            throw ApiException.unprocessable("START_IN_PAST", "Thời điểm bắt đầu đã qua.");
        }
        if (!gameAccounts.acceptsOrders(listing.gameAccountId())) {
            throw ApiException.conflict("ACCOUNT_NOT_AVAILABLE", "Account hiện chưa sẵn sàng nhận đơn mới.");
        }

        long rent;
        if (listing.pricingTiers().isEmpty()) {
            rent = Money.rent(listing.pricePerHour(), durationMinutes);
        } else {
            Long tierPrice = listing.tierPrice(durationMinutes);
            if (tierPrice == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DURATION_NOT_OFFERED",
                        "Listing không có gói thuê với thời lượng này.",
                        Map.of("durations", listing.pricingTiers().stream().map(t -> t.durationMinutes()).toList()));
            }
            rent = tierPrice;
        }
        if (rent <= 0) {
            throw ApiException.unprocessable("DURATION_OUT_OF_RANGE", "Tiền thuê phải lớn hơn 0.");
        }
        long fee = Money.fee(rent, game.platformFeeBps());
        Instant scheduledEnd = startAt.plus(Duration.ofMinutes(durationMinutes));
        // Every mode except FORCE_AT_END keeps the account busy for the grace minutes after the end, so the
        // calendar (exclusion constraint, search, busy periods) blocks that time too.
        Instant blockEnd = scheduledEnd.plus(Duration.ofMinutes(listing.endMode().bufferMinutes(game.graceMinutes())));
        if (startOnPayment) {
            // The real start is only known at payment/approval: reserve the slot up to the latest possible end now,
            // then shrink it to [start, start + duration] when the rental begins.
            int waitMinutes = props.order().paymentHoldMinutes() + (listing.autoAccept() ? 0 : props.order().ownerApprovalMinutes());
            blockEnd = blockEnd.plus(Duration.ofMinutes(waitMinutes));
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("gameCode", game.code());
        snapshot.put("pricePerHour", listing.pricePerHour());
        snapshot.put("durationMinutes", durationMinutes);
        if (!listing.pricingTiers().isEmpty()) {
            snapshot.put("tierPrice", rent);
        }
        snapshot.put("startOnPayment", startOnPayment);
        snapshot.put("depositAmount", listing.depositAmount());
        snapshot.put("endMode", listing.endMode().name());
        snapshot.put("autoAccept", listing.autoAccept());
        snapshot.put("handoverMode", game.handoverMode().name());
        snapshot.put("passwordChangeKicksSessions", game.passwordChangeKicksSessions());
        snapshot.put("platformFeeBps", game.platformFeeBps());
        snapshot.put("depositHoldHours", game.depositHoldHours());
        snapshot.put("sellerHoldHours", game.sellerHoldHours());
        snapshot.put("sellerHoldHoursHighRisk", game.sellerHoldHoursHighRisk());
        snapshot.put("complaintWindowMinutes", game.complaintWindowMinutes());
        snapshot.put("graceMinutes", game.graceMinutes());
        snapshot.put("renterFreeCancelMinutes", listing.effectiveRenterFreeCancelMinutes(game.renterFreeCancelMinutes()));
        snapshot.put("lateCancelRefundBps", game.lateCancelRefundBps());
        snapshot.put("ownerFreeCancelHours", game.ownerFreeCancelHours());
        snapshot.put("risks", riskSnapshot(game.withListingRisks(listing.ownerRisks()), listing));

        Order o = new Order(listingId, listing.gameAccountId(), listing.gameId(), renter.id(), listing.ownerId(),
                startAt, scheduledEnd, blockEnd, rent, fee, listing.depositAmount(), snapshot,
                now.plus(Duration.ofMinutes(props.order().paymentHoldMinutes())), idempotencyKey, now);
        try {
            // Reservation = INSERT. The exclusion constraint is the final arbiter (§15.2).
            orders.saveAndFlush(o);
        } catch (DataIntegrityViolationException e) {
            if (DbErrors.isSqlState(e, DbErrors.EXCLUSION_VIOLATION, "ex_orders_no_overlap")) {
                meters.counter("rentgg_slot_conflicts_total").increment();
                String msg = DbErrors.isSqlState(e, DbErrors.EXCLUSION_VIOLATION, DbErrors.ACCOUNT_BUSY_BY_OWNER)
                        ? "Chủ account đã đánh dấu bận trong khung giờ này." : "Khung giờ đã có người đặt cho account này.";
                throw new ApiException(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE", msg,
                        Map.of("gameAccountId", listing.gameAccountId(), "startAt", startAt.toString(), "endAt", blockEnd.toString()));
            }
            throw e;
        }
        // Each order gets its own deposit (R5): never reused, never shared.
        deposits.create(o.getId(), renterId, listing.depositAmount());
        outbox.record("Order", o.getId(), "OrderCreated", Map.of("orderId", o.getId(), "listingId", listingId,
                "gameAccountId", o.getGameAccountId(), "renterId", renterId, "ownerId", o.getOwnerId(),
                "startAt", startAt, "blockEndAt", blockEnd, "amountToPay", rent + listing.depositAmount()));
        meters.counter("rentgg_orders_created_total", "game", game.code()).increment();
        return new CreateResult(OrderView.of(o), true);
    }

    // ================================================================= pay (UC-07)

    /**
     * Pays the booking from the renter's wallet, in one transaction with the order locked: a double click cannot pay
     * twice, and a replay with the same Idempotency-Key returns the earlier payment.
     */
    public PaymentService.IntentView pay(UUID renterId, UUID orderId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key.");
        }
        return tx.execute(s -> {
            Order order = orders.findForUpdate(orderId).filter(x -> x.getRenterId().equals(renterId))
                    .orElseThrow(() -> ApiException.notFound("Đơn thuê"));
            // Amount always includes this order's own deposit (R5).
            long amount = order.initialRentAmount() + order.getDepositAmount();
            var replay = payments.findIntent(orderId, idempotencyKey);
            if (replay.isPresent()) {
                return replay.get();
            }
            if (order.getStatus() != PENDING_PAYMENT) {
                throw ApiException.invalidState("Đơn không ở trạng thái chờ thanh toán.");
            }
            if (!order.getPaymentExpiresAt().isAfter(clock.instant())) {
                throw ApiException.invalidState("Đơn đã hết thời gian giữ chỗ.");
            }
            return payments.payFromWallet(orderId, renterId, amount, idempotencyKey);
        });
    }

    /** Called by the payment module inside the webhook transaction (§8.1). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentCaptured(UUID intentId, UUID orderId, long amount) {
        Order o = orders.findForUpdate(orderId).orElseThrow();
        // The order's own payment covers the booking only; extensions are paid separately.
        long rent = o.initialRentAmount();
        long dep = o.getDepositAmount();
        if (amount != rent + dep) {
            throw new IllegalStateException("Captured amount does not match order " + orderId);
        }
        ledger.post(TxType.PAYMENT_CAPTURED, orderId, "intent:" + intentId, "PAYMENT_CAPTURED:" + intentId, null,
                List.of(Posting.debit(AccountCode.PSP_CLEARING, amount),
                        Posting.credit(AccountCode.RENT_ESCROW, rent),
                        Posting.credit(AccountCode.DEPOSIT_HELD, dep)));
        Instant now = clock.instant();
        if (o.getStatus() == PENDING_PAYMENT) {
            deposits.markHeld(orderId);
            payments.expireOpenIntents(orderId);
            boolean auto = o.policyBool("autoAccept");
            if (auto) {
                o.moveTo(CONFIRMED, now);
            } else {
                o.awaitApproval(now.plus(Duration.ofMinutes(props.order().ownerApprovalMinutes())), now);
            }
            outbox.record("Order", orderId, "OrderPaid", Map.of("orderId", orderId, "intentId", intentId,
                    "rentAmount", rent, "depositAmount", dep, "nextStatus", o.getStatus(),
                    "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
            if (auto) {
                if (o.startsOnPayment()) {
                    startNowWithinSlot(o, now);
                }
                outbox.record("Order", orderId, "OrderConfirmed", Map.of("orderId", orderId, "startAt", o.getStartAt(),
                        "autoAccepted", true, "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
            } else {
                outbox.record("Order", orderId, "OrderAwaitingApproval", Map.of("orderId", orderId,
                        "ownerId", o.getOwnerId(), "approvalExpiresAt", o.getApprovalExpiresAt()));
            }
            meters.counter("rentgg_orders_transition_total", "from", "PENDING_PAYMENT", "to", o.getStatus().name(),
                    "reason", auto ? "auto_accept" : "manual").increment();
            if (auto) {
                // Start time already reached (always true for "start on payment") → usable right away, without
                // waiting for the next job run. A booking for later stays CONFIRMED until its start time.
                startIfDue(o);
            }
            return;
        }
        // Late or duplicate payment: the slot may already belong to someone else → refund 100% (§8.1).
        ledger.post(TxType.ORDER_CANCELLED_REFUND, orderId, "intent:" + intentId, "LATE_PAYMENT_REFUND:" + intentId, null,
                List.of(Posting.debit(AccountCode.RENT_ESCROW, rent), Posting.debit(AccountCode.DEPOSIT_HELD, dep),
                        Posting.credit(AccountCode.REFUND_PAYABLE, rent + dep)));
        payments.requestRefund(orderId, intentId, "RENT", rent, "LATE_PAYMENT");
        payments.requestRefund(orderId, intentId, "DEPOSIT", dep, "LATE_PAYMENT");
        if (o.getStatus() == EXPIRED) {
            o.setCancelReasonIfAbsent(CancelReason.LATE_PAYMENT, now);
        }
    }

    // ================================================================= owner approval (UC-38)

    @Transactional
    public OrderView approve(UUID ownerId, UUID orderId) {
        Order o = lockAsOwner(ownerId, orderId);
        if (o.getStatus() == CONFIRMED) {
            return OrderView.of(o);
        }
        if (o.getStatus() != AWAITING_OWNER_APPROVAL) {
            throw ApiException.invalidState("Đơn không ở trạng thái chờ duyệt.");
        }
        Instant now = clock.instant();
        o.moveTo(CONFIRMED, now);
        if (o.startsOnPayment()) {
            startNowWithinSlot(o, now);
        }
        outbox.record("Order", orderId, "OrderConfirmed", Map.of("orderId", orderId, "startAt", o.getStartAt(),
                "autoAccepted", false, "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
        // Starts now only if the booked start time has been reached; otherwise the job starts it on time.
        startIfDue(o);
        return OrderView.of(o);
    }

    private void startNowWithinSlot(Order o, Instant now) {
        o.startNowWithin(now, o.policyInt("durationMinutes"), o.endMode().bufferMinutes(o.policyInt("graceMinutes")));
    }

    @Transactional
    public OrderView reject(UUID ownerId, UUID orderId) {
        Order o = lockAsOwner(ownerId, orderId);
        if (o.getStatus() == REJECTED) {
            return OrderView.of(o);
        }
        if (o.getStatus() != AWAITING_OWNER_APPROVAL) {
            throw ApiException.invalidState("Đơn không ở trạng thái chờ duyệt.");
        }
        refundAllBeforeRental(o, REJECTED, CancelReason.OWNER_REJECTED, ownerId);
        outbox.record("Order", orderId, "OrderRejected", Map.of("orderId", orderId, "reason", CancelReason.OWNER_REJECTED,
                "renterId", o.getRenterId()));
        return OrderView.of(o);
    }

    // ================================================================= cancel (UC-17, UC-33, UC-40)

    @Transactional
    public OrderView cancel(UUID callerId, UUID orderId) {
        Order o = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        boolean isRenter = o.getRenterId().equals(callerId);
        boolean isOwner = o.getOwnerId().equals(callerId);
        if (!isRenter && !isOwner) {
            throw ApiException.notFound("Đơn thuê");
        }
        Instant now = clock.instant();
        if (o.getStatus() == CANCELLED && Objects.equals(o.getCancelledBy(), callerId)) {
            return OrderView.of(o);
        }
        switch (o.getStatus()) {
            case PENDING_PAYMENT -> {
                if (!isRenter) {
                    throw ApiException.forbidden("NOT_RESOURCE_OWNER", "Chỉ người thuê được hủy đơn chưa thanh toán.");
                }
                o.cancel(CANCELLED, CancelReason.RENTER_CANCELLED, callerId, now);
                deposits.voidDeposit(orderId);
                payments.expireOpenIntents(orderId);
                emitCancelled(o, callerId, 0, 0);
            }
            case AWAITING_OWNER_APPROVAL, CONFIRMED -> {
                // A "start on payment" order waiting for the owner has not started yet: both sides may cancel, 100% refund.
                boolean notStartedYet = o.startsOnPayment() && o.getStatus() == AWAITING_OWNER_APPROVAL;
                if (!notStartedYet && !now.isBefore(o.getStartAt())) {
                    throw ApiException.invalidState("Đơn đã đến giờ bắt đầu, không thể hủy. Hãy dùng khiếu nại.");
                }
                if (notStartedYet) {
                    refundAllBeforeRental(o, CANCELLED, isRenter ? CancelReason.RENTER_CANCELLED : CancelReason.OWNER_CANCELLED,
                            callerId);
                } else if (isRenter) {
                    renterCancelAfterPayment(o, callerId, now);
                } else {
                    long minutesBefore = Duration.between(now, o.getStartAt()).toMinutes();
                    refundAllBeforeRental(o, CANCELLED, CancelReason.OWNER_CANCELLED, callerId);
                    if (minutesBefore < 60L * o.policyInt("ownerFreeCancelHours")) {
                        signals.record(o.getOwnerId(), "OWNER_LATE_CANCEL", orderId, Map.of("minutesBefore", minutesBefore));
                    }
                }
            }
            default -> throw ApiException.invalidState("Không thể hủy đơn ở trạng thái " + o.getStatus()
                    + (o.getStatus() == IN_RENTAL ? ". Hãy dùng khiếu nại." : "."));
        }
        meters.counter("rentgg_orders_transition_total", "from", "ANY", "to", "CANCELLED",
                "reason", String.valueOf(o.getCancelReason())).increment();
        return OrderView.of(o);
    }

    private void renterCancelAfterPayment(Order o, UUID renterId, Instant now) {
        long minutesBefore = Duration.between(now, o.getStartAt()).toMinutes();
        boolean onTime = minutesBefore >= o.policyInt("renterFreeCancelMinutes");
        long rent = o.getRentAmount();
        long refundRent = onTime ? rent : Money.bps(rent, o.policyInt("lateCancelRefundBps"));
        long kept = rent - refundRent;
        long dep = deposits.markRefundOnCancel(o.getId());
        CancelReason reason = onTime ? CancelReason.RENTER_CANCELLED : CancelReason.RENTER_LATE_CANCELLED;
        o.cancel(CANCELLED, reason, renterId, now);
        ledger.post(TxType.ORDER_CANCELLED_REFUND, o.getId(), reason.name(), "ORDER_CANCELLED_REFUND:" + o.getId(), renterId,
                List.of(Posting.debit(AccountCode.RENT_ESCROW, refundRent), Posting.debit(AccountCode.DEPOSIT_HELD, dep),
                        Posting.credit(AccountCode.REFUND_PAYABLE, refundRent + dep)));
        if (kept > 0) {
            long feeKept = Money.bps(kept, o.policyInt("platformFeeBps"));
            ledger.post(TxType.ORDER_CANCELLED_REVENUE, o.getId(), reason.name(), "ORDER_CANCELLED_REVENUE:" + o.getId(),
                    renterId, List.of(Posting.debit(AccountCode.RENT_ESCROW, kept),
                            Posting.credit(AccountCode.SELLER_REVIEW_HOLD, o.getOwnerId(), kept - feeKept),
                            Posting.credit(AccountCode.PLATFORM_REVENUE, feeKept)));
            o.setSellerHoldUntil(now.plus(Duration.ofHours(sellerHoldHours(o))));
        }
        payments.requestRefund(o.getId(), null, "RENT", refundRent, reason.name());
        payments.requestRefund(o.getId(), null, "DEPOSIT", dep, reason.name());
        emitCancelled(o, renterId, refundRent, dep);
    }

    /**
     * 100% refund of rent and deposit before the rental (owner reject/cancel, approval timeout, not ready,
     * game suspended). Deposit is refunded immediately, never held (§8.8).
     */
    void refundAllBeforeRental(Order o, OrderStatus terminal, CancelReason reason, UUID by) {
        long rent = o.getRentAmount();
        long dep = deposits.markRefundOnCancel(o.getId());
        o.cancel(terminal, reason, by, clock.instant());
        ledger.post(TxType.ORDER_CANCELLED_REFUND, o.getId(), reason.name(), "ORDER_CANCELLED_REFUND:" + o.getId(), by,
                List.of(Posting.debit(AccountCode.RENT_ESCROW, rent), Posting.debit(AccountCode.DEPOSIT_HELD, dep),
                        Posting.credit(AccountCode.REFUND_PAYABLE, rent + dep)));
        payments.requestRefund(o.getId(), null, "RENT", rent, reason.name());
        payments.requestRefund(o.getId(), null, "DEPOSIT", dep, reason.name());
        if (terminal == CANCELLED) {
            emitCancelled(o, by, rent, dep);
        }
    }

    private void emitCancelled(Order o, UUID by, long rentRefund, long depositRefund) {
        Map<String, Object> p = new HashMap<>();
        p.put("orderId", o.getId());
        p.put("cancelReason", o.getCancelReason());
        p.put("renterId", o.getRenterId());
        p.put("ownerId", o.getOwnerId());
        p.put("rentRefund", rentRefund);
        p.put("depositRefund", depositRefund);
        if (by != null) {
            p.put("cancelledBy", by);
        }
        outbox.record("Order", o.getId(), "OrderCancelled", p);
    }

    // ================================================================= jobs' per-order steps (called inside a tx)

    /** expirePendingPayments step. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean expireIfDue(Order o) {
        if (o.getStatus() != PENDING_PAYMENT || o.getPaymentExpiresAt().isAfter(clock.instant())) {
            return false;
        }
        o.moveTo(EXPIRED, clock.instant());
        deposits.voidDeposit(o.getId());
        payments.expireOpenIntents(o.getId());
        outbox.record("Order", o.getId(), "OrderExpired", Map.of("orderId", o.getId(), "renterId", o.getRenterId()));
        return true;
    }

    /** expireOwnerApprovals step. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean rejectIfApprovalTimedOut(Order o) {
        if (o.getStatus() != AWAITING_OWNER_APPROVAL || o.getApprovalExpiresAt().isAfter(clock.instant())) {
            return false;
        }
        refundAllBeforeRental(o, REJECTED, CancelReason.APPROVAL_TIMEOUT, null);
        outbox.record("Order", o.getId(), "OrderRejected", Map.of("orderId", o.getId(),
                "reason", CancelReason.APPROVAL_TIMEOUT, "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
        return true;
    }

    /** startDueOrders step: AVAILABLE + ALLOWED → IN_RENTAL, otherwise cancel with 100% refund. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean startIfDue(Order o) {
        Instant now = clock.instant();
        if (o.getStatus() != CONFIRMED || o.getStartAt().isAfter(now)) {
            return false;
        }
        if (!catalog.get(o.getGameId()).allowed()) {
            refundAllBeforeRental(o, CANCELLED, CancelReason.PLATFORM_GAME_SUSPENDED, null);
            return true;
        }
        if (!gameAccounts.tryMarkInUse(o.getGameAccountId())) {
            refundAllBeforeRental(o, CANCELLED, CancelReason.OWNER_FAULT_NOT_READY, null);
            signals.record(o.getOwnerId(), "OWNER_FAULT_NOT_READY", o.getId(), null);
            meters.counter("rentgg_owner_fault_not_ready_total").increment();
            return true;
        }
        o.moveTo(IN_RENTAL, now);
        outbox.record("Order", o.getId(), "OrderStarted", Map.of("orderId", o.getId(), "gameAccountId", o.getGameAccountId(),
                "blockEndAt", o.getBlockEndAt(), "endMode", o.endMode(), "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
        return true;
    }

    /** endDueOrders step. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean endIfDue(Order o) {
        if (o.getStatus() != IN_RENTAL || o.getBlockEndAt().isAfter(clock.instant())) {
            return false;
        }
        endOrder(o, false);
        return true;
    }

    /** sendEndingReminders step. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean remindIfDue(Order o) {
        Instant now = clock.instant();
        if (o.getStatus() != IN_RENTAL || o.getEndingReminderSentAt() != null
                || o.getScheduledEndAt().minus(Duration.ofMinutes(props.order().endingReminderMinutes())).isAfter(now)) {
            return false;
        }
        o.markEndingReminderSent(now);
        outbox.record("Order", o.getId(), "OrderEndingSoon", Map.of("orderId", o.getId(), "scheduledEndAt",
                o.getScheduledEndAt(), "endMode", o.endMode(), "renterId", o.getRenterId(), "ownerId", o.getOwnerId()));
        return true;
    }

    /**
     * Ends an order: ORDER_ENDED moves the remaining rent escrow to the seller's review hold and the platform
     * fee to revenue. For a takeover the deposit/account are handled by the dispute module.
     */
    private void endOrder(Order o, boolean takeover) {
        Instant now = clock.instant();
        extensions.closePendingOnEnd(o);
        o.end(now);
        long escrow = ledger.liabilityBalanceForOrder(AccountCode.RENT_ESCROW, null, o.getId());
        if (escrow > 0) {
            long fee = escrow == o.getRentAmount() ? o.getPlatformFee() : Money.fee(escrow, o.policyInt("platformFeeBps"));
            ledger.post(TxType.ORDER_ENDED, o.getId(), null, "ORDER_ENDED:" + o.getId(), null,
                    List.of(Posting.debit(AccountCode.RENT_ESCROW, escrow),
                            Posting.credit(AccountCode.SELLER_REVIEW_HOLD, o.getOwnerId(), escrow - fee),
                            Posting.credit(AccountCode.PLATFORM_REVENUE, fee)));
        }
        Instant sellerHoldUntil = now.plus(Duration.ofHours(sellerHoldHours(o)));
        o.setSellerHoldUntil(sellerHoldUntil);
        Instant depositHoldUntil = now.plus(Duration.ofHours(o.policyInt("depositHoldHours")));
        if (!takeover) {
            deposits.startPostRentalHold(o.getId(), depositHoldUntil);
            gameAccounts.onOrderEnded(o.getGameAccountId(), o.endMode() == EndMode.STAY_ONLINE_UNTIL_RECLAIM);
        }
        outbox.record("Order", o.getId(), "OrderEnded", Map.of("orderId", o.getId(), "actualEndAt", now,
                "endMode", o.endMode(), "depositHoldUntil", depositHoldUntil, "sellerHoldUntil", sellerHoldUntil,
                "renterId", o.getRenterId(), "ownerId", o.getOwnerId(), "takeover", takeover));
        OrderView view = OrderView.of(o);
        lifecycleListeners.orderedStream().forEach(l -> l.afterOrderEnded(view));
    }

    private int sellerHoldHours(Order o) {
        boolean high = users.get(o.getOwnerId()).highRisk();
        return high ? o.policyInt("sellerHoldHoursHighRisk") : o.policyInt("sellerHoldHours");
    }

    // ================================================================= calls from dispute / wallet

    /** UC-43: owner reports takeover during the rental → end immediately (revenue still recognised). */
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView endForTakeover(UUID orderId) {
        Order o = orders.findForUpdate(orderId).orElseThrow();
        if (o.getStatus() == IN_RENTAL) {
            endOrder(o, true);
        }
        return OrderView.of(o);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderView lockView(UUID orderId) {
        return OrderView.of(orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Đơn thuê")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markSellerReleased(UUID orderId) {
        orders.findForUpdate(orderId).orElseThrow().markSellerReleased(clock.instant());
    }

    @Transactional(readOnly = true)
    public List<UUID> sellerHoldDue(int limit) {
        return orders.findSellerHoldDue(clock.instant(), limit);
    }

    // ================================================================= queries

    @Transactional(readOnly = true)
    public OrderView getInternal(UUID orderId) {
        return OrderView.of(orders.findById(orderId).orElseThrow(() -> ApiException.notFound("Đơn thuê")));
    }

    /** Renter/owner of the order, SUPPORT or ADMIN; anyone else gets 404. */
    @Transactional(readOnly = true)
    public OrderView getForCaller(UUID callerId, UUID orderId) {
        OrderView o = getInternal(orderId);
        if (!o.renterId().equals(callerId) && !o.ownerId().equals(callerId)
                && !CurrentUser.has(Permission.ORDER_READ_ANY)) {
            throw ApiException.notFound("Đơn thuê");
        }
        return o;
    }

    @Transactional(readOnly = true)
    public Page<OrderView> list(UUID userId, String role, OrderStatus status, Pageable pageable) {
        boolean owner = "OWNER".equalsIgnoreCase(role);
        Page<Order> page = owner
                ? (status == null ? orders.findByOwnerId(userId, pageable) : orders.findByOwnerIdAndStatus(userId, status, pageable))
                : (status == null ? orders.findByRenterId(userId, pageable) : orders.findByRenterIdAndStatus(userId, status, pageable));
        return page.map(OrderView::of);
    }

    // ================================================================= helpers

    private Order lockAsOwner(UUID ownerId, UUID orderId) {
        Order o = orders.findForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Đơn thuê"));
        if (!o.getOwnerId().equals(ownerId)) {
            if (o.getRenterId().equals(ownerId)) {
                throw ApiException.forbidden("NOT_RESOURCE_OWNER", "Chỉ chủ account được thực hiện thao tác này.");
            }
            throw ApiException.notFound("Đơn thuê");
        }
        return o;
    }
}
