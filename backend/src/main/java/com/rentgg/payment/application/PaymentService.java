package com.rentgg.payment.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.config.RentGgProperties;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.payment.domain.PaymentIntent;
import com.rentgg.payment.domain.Refund;
import com.rentgg.payment.infrastructure.PaymentIntentRepository;
import com.rentgg.payment.infrastructure.RefundRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.DomainEvent;
import com.rentgg.shared.outbox.OutboxService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final int MAX_REFUND_ATTEMPTS = 10;

    private final PaymentIntentRepository intents;
    private final RefundRepository refunds;
    private final PaymentGateways gateways;
    private final ObjectProvider<PaymentCapturedHandler> capturedHandler;
    private final LedgerService ledger;
    private final OutboxService outbox;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final RentGgProperties props;
    private final Clock clock;

    public PaymentService(PaymentIntentRepository intents, RefundRepository refunds, PaymentGateways gateways,
                          ObjectProvider<PaymentCapturedHandler> capturedHandler, LedgerService ledger,
                          OutboxService outbox, AuditService audit, TransactionTemplate tx, MeterRegistry meters,
                          RentGgProperties props, Clock clock) {
        this.intents = intents;
        this.refunds = refunds;
        this.gateways = gateways;
        this.capturedHandler = capturedHandler;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.tx = tx;
        this.meters = meters;
        this.props = props;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- wallet top-up (PSP checkout)

    public static final long MIN_TOPUP = 10_000;
    public static final long MAX_TOPUP = 50_000_000;

    /**
     * Creates (or replays) a top-up intent (no order) and calls the PSP outside of a long transaction (§15.1).
     * The money reaches the wallet when the PSP confirms the payment (webhook).
     */
    public IntentView createTopup(UUID payerId, long amount, String idempotencyKey) {
        return createTopup(payerId, amount, idempotencyKey, null, null);
    }

    /** {@code provider == null} → the default provider; {@code clientIp} is passed to providers that want it. */
    public IntentView createTopup(UUID payerId, long amount, String idempotencyKey, String provider, String clientIp) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key.");
        }
        if (amount < MIN_TOPUP || amount > MAX_TOPUP) {
            throw ApiException.badRequest("VALIDATION_ERROR",
                    "Số tiền nạp phải từ " + MIN_TOPUP + "đ đến " + MAX_TOPUP + "đ.");
        }
        PaymentGateway gateway = gateways.require(provider);
        IntentView existing = tx.execute(s -> intents.findTopup(payerId, idempotencyKey).map(IntentView::of).orElse(null));
        if (existing != null && (existing.amount() != amount || !existing.provider().equals(gateway.provider()))) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key đã dùng cho yêu cầu khác.");
        }
        if (existing != null && existing.checkoutUrl() != null) {
            return existing;
        }
        UUID intentId = existing != null ? existing.id() : tx.execute(s -> {
            PaymentIntent pi = new PaymentIntent(null, payerId, gateway.provider(), amount, idempotencyKey, clock.instant());
            intents.saveAndFlush(pi);
            return pi.getId();
        });
        String base = props.payment().publicBaseUrl() + "/api/v1/payments/";
        String code = gateway.provider().toLowerCase(java.util.Locale.ROOT);
        PaymentGateway.Checkout checkout;
        try {
            // Providers run on real time, not the (possibly fast-forwarded) business clock.
            checkout = gateway.createCheckout(new PaymentGateway.CheckoutRequest(intentId, amount, "VieGG wallet top-up",
                    base + "return/" + code, base + "webhook/" + code, clientIp,
                    Instant.now().plus(Duration.ofMinutes(15))));
        } catch (PaymentGateway.ProviderException e) {
            log.warn("Checkout with {} failed for intent {}: {}", gateway.provider(), intentId, e.getMessage());
            meters.counter("rentgg_payment_checkouts_total", "provider", gateway.provider(), "result", "error").increment();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", e.getMessage());
        }
        meters.counter("rentgg_payment_checkouts_total", "provider", gateway.provider(), "result", "ok").increment();
        return tx.execute(s -> {
            PaymentIntent pi = intents.findById(intentId).orElseThrow();
            pi.attachCheckout(checkout.providerRef(), checkout.checkoutUrl(), clock.instant());
            return IntentView.of(pi);
        });
    }

    @Transactional(readOnly = true)
    public List<IntentView> recentTopups(UUID payerId, int limit) {
        return intents.findTopups(payerId, org.springframework.data.domain.PageRequest.of(0, limit)).stream()
                .map(IntentView::of).toList();
    }

    // ---------------------------------------------------------------- pay from wallet

    /**
     * Pays an order or extension from the payer's wallet, in the caller's transaction: the caller has locked the
     * order and checked it is payable. Replays with the same key return the earlier payment. Not enough balance →
     * 422 INSUFFICIENT_WALLET_BALANCE (nothing is written).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public IntentView payFromWallet(UUID orderId, UUID payerId, long amount, String idempotencyKey) {
        var existing = intents.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getAmount() != amount) {
                throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key đã dùng cho yêu cầu khác.");
            }
            return IntentView.of(existing.get());
        }
        ledger.lockSellerAvailable(payerId);
        long balance = ledger.liabilityBalance(AccountCode.SELLER_AVAILABLE, payerId);
        if (balance < amount) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_WALLET_BALANCE",
                    "Số dư ví không đủ. Hãy nạp thêm " + (amount - balance) + "đ.",
                    Map.of("balance", balance, "required", amount, "shortfall", amount - balance));
        }
        Instant now = clock.instant();
        PaymentIntent pi = new PaymentIntent(orderId, payerId, PaymentIntent.WALLET, amount, idempotencyKey, now);
        pi.attachCheckout("wallet_" + pi.getId(), null, now);
        pi.setStatus(PaymentIntent.SUCCEEDED, now);
        intents.saveAndFlush(pi);
        // Wallet → clearing here; the order module's PAYMENT_CAPTURED then moves clearing → escrow/deposit, the same
        // way as a PSP payment.
        ledger.post(TxType.WALLET_PAYMENT, orderId, "intent:" + pi.getId(), "WALLET_PAYMENT:" + pi.getId(), payerId,
                List.of(Posting.debit(AccountCode.SELLER_AVAILABLE, payerId, amount),
                        Posting.credit(AccountCode.PSP_CLEARING, amount)));
        handler().onPaymentCaptured(pi.getId(), orderId, amount, idempotencyKey);
        meters.counter("rentgg_wallet_payments_total").increment();
        return IntentView.of(pi);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<IntentView> findIntent(UUID orderId, String idempotencyKey) {
        return intents.findByOrderIdAndIdempotencyKey(orderId, idempotencyKey).map(IntentView::of);
    }

    private PaymentCapturedHandler handler() {
        PaymentCapturedHandler handler = capturedHandler.getIfAvailable();
        if (handler == null) {
            throw new IllegalStateException("No PaymentCapturedHandler registered");
        }
        return handler;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void expireOpenIntents(UUID orderId) {
        intents.expireOpenIntents(orderId, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<IntentView> intentsForOrder(UUID orderId) {
        return intents.findByOrderIdOrderByCreatedAt(orderId).stream().map(IntentView::of).toList();
    }

    // ---------------------------------------------------------------- webhook

    /** Configured providers, the default one first. */
    public List<ProviderView> providers() {
        return gateways.available().stream()
                .map(g -> new ProviderView(g.provider(), g.displayName(), g.sandbox())).toList();
    }

    public record ProviderView(String code, String name, boolean sandbox) {}

    /**
     * Provider's server-to-server notification: checks the signature, then applies the result idempotently
     * (§12.6). Returns what the provider expects as an answer.
     */
    public PaymentGateway.Ack handleWebhook(String provider, PaymentGateway.CallbackRequest req) {
        PaymentGateway gateway = gateways.find(provider).orElseThrow(() -> ApiException.notFound("Provider"));
        PaymentGateway.Verified verified;
        try {
            verified = gateway.verifyCallback(req);
        } catch (IllegalArgumentException e) {
            meters.counter("rentgg_payment_webhooks_total", "provider", gateway.provider(), "result", "malformed").increment();
            return gateway.ack(PaymentGateway.Outcome.MALFORMED);
        }
        if (!verified.signatureValid()) {
            meters.counter("rentgg_payment_webhooks_total", "provider", gateway.provider(), "result", "invalid_signature").increment();
            log.warn("Webhook with invalid signature from provider {}", gateway.provider());
            audit.recordIndependent(null, "WEBHOOK_SIGNATURE_INVALID", "Payment", null,
                    Map.of("provider", gateway.provider()), null);
            return gateway.ack(PaymentGateway.Outcome.INVALID_SIGNATURE);
        }
        PaymentGateway.WebhookPayload payload = verified.payload();
        if (payload == null || payload.providerRef() == null || payload.status() == null) {
            return gateway.ack(PaymentGateway.Outcome.MALFORMED);
        }
        PaymentGateway.Outcome outcome = tx.execute(s -> applyPaymentResult(gateway.provider(), payload));
        meters.counter("rentgg_payment_webhooks_total", "provider", gateway.provider(),
                "result", outcome.name().toLowerCase(java.util.Locale.ROOT)).increment();
        return gateway.ack(outcome);
    }

    /**
     * Payer's browser coming back from the provider. Applies the (verified) result like a webhook, so a sandbox
     * payment completes on localhost where the provider's IPN cannot reach us. Never throws: the caller always
     * redirects the payer to the wallet page.
     */
    public ReturnResult handleReturn(String provider, PaymentGateway.CallbackRequest req) {
        PaymentGateway gateway = gateways.find(provider).orElse(null);
        if (gateway == null) {
            return new ReturnResult(null, "UNKNOWN");
        }
        try {
            PaymentGateway.Verified v = gateway.verifyReturn(req);
            if (!v.signatureValid() || v.payload() == null || v.payload().providerRef() == null) {
                log.warn("Payment return from {} could not be verified", gateway.provider());
                return new ReturnResult(null, "UNKNOWN");
            }
            PaymentGateway.WebhookPayload p = v.payload();
            if (p.status() != null) {
                tx.execute(s -> applyPaymentResult(gateway.provider(), p));
            }
            return tx.execute(s -> intents.findByProviderAndProviderRef(gateway.provider(), p.providerRef())
                    .map(i -> new ReturnResult(i.getId(), i.getStatus()))
                    .orElse(new ReturnResult(null, "UNKNOWN")));
        } catch (RuntimeException e) {
            log.warn("Payment return from {} failed: {}", gateway.provider(), e.toString());
            return new ReturnResult(null, "UNKNOWN");
        }
    }

    public record ReturnResult(UUID intentId, String status) {}

    /** Dev profile: same handler as a valid webhook (§8.1). */
    public void simulateSuccess(UUID intentId, UUID callerId) {
        IntentView intent = tx.execute(s -> intents.findById(intentId).map(IntentView::of).orElse(null));
        if (intent == null || !intent.payerId().equals(callerId)) {
            throw ApiException.notFound("Payment intent");
        }
        if (intent.providerRef() == null) {
            throw ApiException.invalidState("Payment intent chưa có checkout.");
        }
        tx.execute(s -> applyPaymentResult(intent.provider(),
                new PaymentGateway.WebhookPayload(intent.providerRef(), "SUCCEEDED", intent.amount())));
    }

    private PaymentGateway.Outcome applyPaymentResult(String provider, PaymentGateway.WebhookPayload p) {
        PaymentIntent intent = intents.findByProviderRefForUpdate(provider, p.providerRef()).orElse(null);
        if (intent == null) {
            log.warn("Webhook for unknown providerRef {}", p.providerRef());
            return PaymentGateway.Outcome.UNKNOWN_REF;
        }
        if (PaymentIntent.SUCCEEDED.equals(intent.getStatus())) {
            return PaymentGateway.Outcome.DUPLICATE;
        }
        if ("PENDING".equalsIgnoreCase(p.status())) {
            return PaymentGateway.Outcome.PENDING;
        }
        if (!"SUCCEEDED".equalsIgnoreCase(p.status())) {
            if (PaymentIntent.CREATED.equals(intent.getStatus())) {
                intent.setStatus(PaymentIntent.FAILED, clock.instant());
            }
            return PaymentGateway.Outcome.FAILED;
        }
        if (p.amount() != intent.getAmount()) {
            log.warn("Webhook amount mismatch for intent {}: expected {}, got {}", intent.getId(), intent.getAmount(), p.amount());
            audit.record(null, "WEBHOOK_AMOUNT_MISMATCH", "PaymentIntent", intent.getId(),
                    null, Map.of("expected", intent.getAmount(), "received", p.amount()), null);
            return PaymentGateway.Outcome.AMOUNT_MISMATCH;
        }
        // A FAILED/EXPIRED intent that is paid anyway is still money received: capture, then the order
        // module decides (normally a late-payment refund).
        intent.setStatus(PaymentIntent.SUCCEEDED, clock.instant());
        intents.flush();
        if (intent.isTopup()) {
            ledger.post(TxType.WALLET_TOPUP, null, "intent:" + intent.getId(), "WALLET_TOPUP:" + intent.getId(), null,
                    List.of(Posting.debit(AccountCode.PSP_CLEARING, intent.getAmount()),
                            Posting.credit(AccountCode.SELLER_AVAILABLE, intent.getPayerId(), intent.getAmount())));
            outbox.record("Wallet", intent.getPayerId(), "WalletToppedUp", Map.of("intentId", intent.getId(),
                    "userId", intent.getPayerId(), "amount", intent.getAmount()));
            meters.counter("rentgg_wallet_topups_total").increment();
            return PaymentGateway.Outcome.OK;
        }
        handler().onPaymentCaptured(intent.getId(), intent.getOrderId(), intent.getAmount(), intent.getIdempotencyKey());
        return PaymentGateway.Outcome.OK;
    }

    // ---------------------------------------------------------------- refunds

    /**
     * Creates PENDING refund(s) and emits RefundRequested. With {@code intentId == null} the amount is spread over
     * the order's successful payments (oldest first), never refunding more than a payment received: an extended
     * order has one payment for the booking and one per extension.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requestRefund(UUID orderId, UUID intentId, String kind, long amount, String reason) {
        if (amount <= 0) {
            return null;
        }
        if (intentId != null) {
            return createRefund(orderId, intentId, kind, amount, reason);
        }
        List<PaymentIntent> paid = intents.findByOrderIdOrderByCreatedAt(orderId).stream()
                .filter(i -> PaymentIntent.SUCCEEDED.equals(i.getStatus())).toList();
        if (paid.isEmpty()) {
            throw new IllegalStateException("No successful payment for order " + orderId);
        }
        Map<UUID, Long> refunded = new java.util.HashMap<>();
        refunds.findByOrderIdOrderByCreatedAt(orderId)
                .forEach(r -> refunded.merge(r.getPaymentIntentId(), r.getAmount(), Long::sum));
        UUID first = null;
        long left = amount;
        for (PaymentIntent pi : paid) {
            long room = pi.getAmount() - refunded.getOrDefault(pi.getId(), 0L);
            long part = Math.min(room, left);
            if (part <= 0) {
                continue;
            }
            UUID id = createRefund(orderId, pi.getId(), kind, part, reason);
            first = first == null ? id : first;
            left -= part;
            if (left == 0) {
                return first;
            }
        }
        // More than was ever received (should not happen): keep the old behaviour and put the rest on the last payment.
        UUID id = createRefund(orderId, paid.get(paid.size() - 1).getId(), kind, left, reason);
        return first == null ? id : first;
    }

    private UUID createRefund(UUID orderId, UUID payIntent, String kind, long amount, String reason) {
        PaymentIntent pi = intents.findById(payIntent).orElseThrow();
        Refund r = new Refund(orderId, payIntent, kind, amount, reason, clock.instant());
        refunds.save(r);
        outbox.record("Refund", r.getId(), "RefundRequested", Map.of("refundId", r.getId(), "orderId", orderId,
                "kind", kind, "amount", amount, "intentId", payIntent, "renterId", pi.getPayerId()));
        return r.getId();
    }

    @EventListener
    public void onRefundRequested(DomainEvent event) {
        if (event.is("RefundRequested")) {
            processRefund(event.uuid("refundId"));
        }
    }

    /**
     * Credits the refund to the payer's wallet (every refund goes to the wallet, whatever paid the order).
     * Idempotent: only PENDING/FAILED refunds are processed.
     */
    @Transactional
    public void processRefund(UUID refundId) {
        Refund r = refunds.findForUpdate(refundId).orElseThrow();
        if (Refund.SUCCEEDED.equals(r.getStatus())) {
            return;
        }
        PaymentIntent pi = intents.findById(r.getPaymentIntentId()).orElseThrow();
        r.succeeded("wallet", clock.instant());
        ledger.post(TxType.REFUND_COMPLETED, r.getOrderId(), "refund:" + r.getId(), "REFUND_COMPLETED:" + r.getId(),
                null, List.of(Posting.debit(AccountCode.REFUND_PAYABLE, r.getAmount()),
                        Posting.credit(AccountCode.SELLER_AVAILABLE, pi.getPayerId(), r.getAmount())));
        outbox.record("Refund", r.getId(), "RefundCompleted", Map.of("refundId", r.getId(), "orderId", r.getOrderId(),
                "kind", r.getKind(), "amount", r.getAmount(), "renterId", pi.getPayerId()));
        meters.counter("rentgg_refunds_total", "kind", r.getKind(), "result", "succeeded").increment();
    }

    /** Retries FAILED refunds with exponential backoff (job). */
    public int retryFailedRefunds(int batchSize) {
        int n = 0;
        for (UUID id : refunds.findRetryable(MAX_REFUND_ATTEMPTS, clock.instant().minus(Duration.ofMinutes(1)), batchSize)) {
            Refund r = refunds.findById(id).orElse(null);
            if (r == null) {
                continue;
            }
            long backoffSeconds = Math.min(3600, 60L * (1L << Math.min(r.getAttempts(), 6)));
            if (r.getUpdatedAt().plusSeconds(backoffSeconds).isAfter(clock.instant())) {
                continue;
            }
            try {
                tx.executeWithoutResult(s -> processRefund(id));
                n++;
            } catch (RuntimeException e) {
                log.warn("Refund retry {} failed: {}", id, e.toString());
            }
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<RefundView> refundsForOrder(UUID orderId) {
        return refunds.findByOrderIdOrderByCreatedAt(orderId).stream().map(RefundView::of).toList();
    }

    public record IntentView(UUID id, UUID orderId, UUID payerId, String provider, String providerRef, long amount,
                             String status, String checkoutUrl, Instant createdAt) {
        static IntentView of(PaymentIntent p) {
            return new IntentView(p.getId(), p.getOrderId(), p.getPayerId(), p.getProvider(), p.getProviderRef(),
                    p.getAmount(), p.getStatus(), p.getCheckoutUrl(), p.getCreatedAt());
        }
    }

    public record RefundView(UUID id, String kind, long amount, String status, String reason, int attempts) {
        static RefundView of(Refund r) {
            return new RefundView(r.getId(), r.getKind(), r.getAmount(), r.getStatus(), r.getReason(), r.getAttempts());
        }
    }
}
