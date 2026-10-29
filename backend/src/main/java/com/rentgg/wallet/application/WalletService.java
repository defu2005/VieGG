package com.rentgg.wallet.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.dispute.application.DisputeService;
import com.rentgg.identity.application.UserService;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.order.application.OrderService;
import com.rentgg.order.application.OrderView;
import com.rentgg.settings.application.SettingsService;
import com.rentgg.settings.domain.Setting;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import com.rentgg.wallet.domain.Withdrawal;
import com.rentgg.wallet.infrastructure.WithdrawalRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Seller balances (projection of the ledger), withdrawals (§8.9, §15.3) and revenue hold release (UC-41). */
@Service
public class WalletService {

    private final WithdrawalRepository withdrawals;
    private final LedgerService ledger;
    private final OrderService orders;
    private final DisputeService disputes;
    private final UserService users;
    private final AuditService audit;
    private final OutboxService outbox;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;
    private final SettingsService settings;
    private final Clock clock;

    public WalletService(WithdrawalRepository withdrawals, LedgerService ledger, OrderService orders,
                         DisputeService disputes, UserService users, AuditService audit, OutboxService outbox,
                         MeterRegistry meters, TransactionTemplate tx, SettingsService settings, Clock clock) {
        this.withdrawals = withdrawals;
        this.ledger = ledger;
        this.orders = orders;
        this.disputes = disputes;
        this.users = users;
        this.audit = audit;
        this.outbox = outbox;
        this.meters = meters;
        this.tx = tx;
        this.settings = settings;
        this.clock = clock;
    }

    public record Balances(long reviewHold, long disputeHold, long available, long payoutProcessing, String currency) {}

    @Transactional(readOnly = true)
    public Balances balances(UUID sellerId) {
        Map<AccountCode, Long> b = ledger.sellerBalances(sellerId);
        return new Balances(b.get(AccountCode.SELLER_REVIEW_HOLD), b.get(AccountCode.SELLER_DISPUTE_HOLD),
                b.get(AccountCode.SELLER_AVAILABLE), b.get(AccountCode.SELLER_PAYOUT_PROCESSING), "VND");
    }

    // ================================================================= withdrawals

    public record BankAccount(String bankName, String bankAccountNumber, String bankAccountName) {
        @Override
        public String toString() {
            return "BankAccount[bankName=" + bankName + ", number=***]";
        }
    }

    public record WithdrawalResult(Withdrawal withdrawal, boolean created) {}

    @Transactional
    public WithdrawalResult requestWithdrawal(UUID ownerId, long amount, BankAccount bank, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Thiếu header Idempotency-Key.");
        }
        if (amount <= 0) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Số tiền rút phải > 0.");
        }
        var existing = withdrawals.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existing.isPresent()) {
            return new WithdrawalResult(replay(existing.get(), amount, bank), false);
        }
        checkWithdrawalLimits(amount);
        users.requireEligible(ownerId, 18, true);
        // Serialize all operations that decrease this seller's available balance (§15.3).
        ledger.lockSellerAvailable(ownerId);
        existing = withdrawals.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
        if (existing.isPresent()) {
            return new WithdrawalResult(replay(existing.get(), amount, bank), false);
        }
        long available = ledger.liabilityBalance(AccountCode.SELLER_AVAILABLE, ownerId);
        if (amount > available) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_AVAILABLE_BALANCE",
                    "Số dư khả dụng không đủ.", Map.of("available", available));
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("bankName", bank.bankName());
        snapshot.put("bankAccountNumber", bank.bankAccountNumber());
        snapshot.put("bankAccountName", bank.bankAccountName());
        Withdrawal w = new Withdrawal(ownerId, amount, snapshot, idempotencyKey, clock.instant());
        try {
            withdrawals.saveAndFlush(w);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Yêu cầu rút tiền đang được xử lý.");
        }
        ledger.post(TxType.WITHDRAWAL_REQUESTED, null, "withdrawal:" + w.getId(), "WITHDRAWAL_REQUESTED:" + w.getId(),
                ownerId, List.of(Posting.debit(AccountCode.SELLER_AVAILABLE, ownerId, amount),
                        Posting.credit(AccountCode.SELLER_PAYOUT_PROCESSING, ownerId, amount)));
        outbox.record("Withdrawal", w.getId(), "WithdrawalRequested", Map.of("withdrawalId", w.getId(),
                "ownerId", ownerId, "amount", amount));
        meters.counter("rentgg_withdrawals_requested_amount").increment(amount);
        return new WithdrawalResult(w, true);
    }

    /** Admin-configured rules (Setting.WITHDRAWAL_*); a replay of an accepted request is not re-checked. */
    private void checkWithdrawalLimits(long amount) {
        if (!settings.getBoolean(Setting.WITHDRAWAL_ENABLED)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_DISABLED",
                    "Tính năng rút tiền đang tạm ngưng. Vui lòng thử lại sau.");
        }
        long min = settings.getLong(Setting.WITHDRAWAL_MIN_AMOUNT);
        if (amount < min) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_BELOW_MINIMUM",
                    "Số tiền rút tối thiểu là " + vnd(min) + ".", Map.of("minAmount", min));
        }
        long max = settings.getLong(Setting.WITHDRAWAL_MAX_AMOUNT);
        if (max > 0 && amount > max) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WITHDRAWAL_ABOVE_MAXIMUM",
                    "Mỗi lần rút tối đa " + vnd(max) + ".", Map.of("maxAmount", max));
        }
    }

    private static String vnd(long amount) {
        return String.format(java.util.Locale.ROOT, "%,d", amount).replace(',', '.') + " đ";
    }

    private Withdrawal replay(Withdrawal w, long amount, BankAccount bank) {
        if (w.getAmount() != amount
                || !Objects.equals(w.getBankSnapshot().get("bankAccountNumber"), bank.bankAccountNumber())) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency-Key đã được dùng cho yêu cầu khác.");
        }
        return w;
    }

    @Transactional
    public Withdrawal markPaid(UUID financeId, UUID withdrawalId, String bankRef) {
        if (bankRef == null || bankRef.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "bankRef là bắt buộc.");
        }
        Withdrawal w = withdrawals.findForUpdate(withdrawalId).orElseThrow(() -> ApiException.notFound("Yêu cầu rút tiền"));
        if (Withdrawal.PAID.equals(w.getStatus())) {
            return w;
        }
        if (!Withdrawal.REQUESTED.equals(w.getStatus())) {
            throw ApiException.invalidState("Yêu cầu rút tiền không ở trạng thái REQUESTED.");
        }
        Instant now = clock.instant();
        w.markPaid(financeId, bankRef, now);
        ledger.post(TxType.WITHDRAWAL_PAID, null, "withdrawal:" + w.getId(), "WITHDRAWAL_PAID:" + w.getId(), financeId,
                List.of(Posting.debit(AccountCode.SELLER_PAYOUT_PROCESSING, w.getOwnerId(), w.getAmount()),
                        Posting.credit(AccountCode.PSP_CLEARING, w.getAmount())));
        audit.record(financeId, "WITHDRAWAL_MARKED_PAID", "Withdrawal", w.getId(), Map.of("status", "REQUESTED"),
                Map.of("status", "PAID", "bankRef", bankRef), null);
        outbox.record("Withdrawal", w.getId(), "WithdrawalPaid", Map.of("withdrawalId", w.getId(),
                "ownerId", w.getOwnerId(), "amount", w.getAmount()));
        meters.timer("rentgg_withdrawal_payout_latency_seconds").record(Duration.between(w.getCreatedAt(), now));
        return w;
    }

    @Transactional
    public Withdrawal markFailed(UUID financeId, UUID withdrawalId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reason là bắt buộc.");
        }
        Withdrawal w = withdrawals.findForUpdate(withdrawalId).orElseThrow(() -> ApiException.notFound("Yêu cầu rút tiền"));
        if (Withdrawal.FAILED.equals(w.getStatus())) {
            return w;
        }
        if (!Withdrawal.REQUESTED.equals(w.getStatus())) {
            throw ApiException.invalidState("Yêu cầu rút tiền không ở trạng thái REQUESTED.");
        }
        w.markFailed(financeId, reason, clock.instant());
        ledger.post(TxType.WITHDRAWAL_FAILED, null, "withdrawal:" + w.getId(), "WITHDRAWAL_FAILED:" + w.getId(), financeId,
                List.of(Posting.debit(AccountCode.SELLER_PAYOUT_PROCESSING, w.getOwnerId(), w.getAmount()),
                        Posting.credit(AccountCode.SELLER_AVAILABLE, w.getOwnerId(), w.getAmount())));
        audit.record(financeId, "WITHDRAWAL_MARKED_FAILED", "Withdrawal", w.getId(), Map.of("status", "REQUESTED"),
                Map.of("status", "FAILED", "reason", reason), null);
        outbox.record("Withdrawal", w.getId(), "WithdrawalFailed", Map.of("withdrawalId", w.getId(),
                "ownerId", w.getOwnerId(), "amount", w.getAmount(), "reason", reason));
        return w;
    }

    @Transactional(readOnly = true)
    public Page<Withdrawal> listByStatus(String status, Pageable pageable) {
        return listByStatus(status, null, pageable);
    }

    /** Finance list; {@code ownerId} narrows it to one user's withdrawals. */
    @Transactional(readOnly = true)
    public Page<Withdrawal> listByStatus(String status, UUID ownerId, Pageable pageable) {
        if (ownerId != null) {
            return status == null ? withdrawals.findByOwnerId(ownerId, pageable)
                    : withdrawals.findByStatusAndOwnerId(status, ownerId, pageable);
        }
        return status == null ? withdrawals.findAll(pageable) : withdrawals.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Withdrawal> listMine(UUID ownerId, Pageable pageable) {
        return withdrawals.findByOwnerId(ownerId, pageable);
    }

    // ================================================================= revenue hold

    /** releaseSellerHolds job (§9): seller_hold_until passed and no renter dispute open. */
    public int releaseSellerHolds(int batchSize) {
        int n = 0;
        for (UUID orderId : orders.sellerHoldDue(batchSize)) {
            Boolean done = tx.execute(s -> releaseHold(orderId, null, false));
            if (Boolean.TRUE.equals(done)) {
                n++;
            }
        }
        return n;
    }

    /** Executed by an approved SELLER_HOLD_EARLY_RELEASE (§8.10). */
    @Transactional
    public void releaseSellerHoldEarly(UUID orderId, UUID approvedBy) {
        if (!releaseHold(orderId, approvedBy, true)) {
            throw ApiException.invalidState("Không thể giải phóng doanh thu của đơn này.");
        }
    }

    private boolean releaseHold(UUID orderId, UUID approvedBy, boolean early) {
        OrderView o = orders.lockView(orderId);
        if (o.sellerReleasedAt() != null || o.sellerHoldUntil() == null) {
            return false;
        }
        if (!early && o.sellerHoldUntil().isAfter(clock.instant())) {
            return false;
        }
        if (disputes.hasOpenRenterDispute(orderId)) {
            if (early) {
                throw ApiException.invalidState("Đơn đang có khiếu nại của người thuê.");
            }
            return false;
        }
        long amount = ledger.liabilityBalanceForOrder(AccountCode.SELLER_REVIEW_HOLD, o.ownerId(), orderId);
        if (amount > 0) {
            ledger.post(TxType.SELLER_HOLD_RELEASED, orderId, early ? "early-release" : "hold-expired",
                    "SELLER_HOLD_RELEASED:" + orderId, approvedBy,
                    List.of(Posting.debit(AccountCode.SELLER_REVIEW_HOLD, o.ownerId(), amount),
                            Posting.credit(AccountCode.SELLER_AVAILABLE, o.ownerId(), amount)));
            outbox.record("Order", orderId, "SellerHoldReleased", Map.of("orderId", orderId, "sellerId", o.ownerId(),
                    "amount", amount));
        }
        orders.markSellerReleased(orderId);
        return true;
    }
}
