package com.rentgg.deposit.application;

import com.rentgg.deposit.domain.Deposit;
import com.rentgg.deposit.domain.DepositStatus;
import com.rentgg.deposit.infrastructure.DepositRepository;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.ledger.domain.AccountCode;
import com.rentgg.ledger.domain.Posting;
import com.rentgg.ledger.domain.RiskPayoutFee;
import com.rentgg.ledger.domain.TxType;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.DomainEvent;
import com.rentgg.shared.outbox.OutboxService;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.rentgg.deposit.domain.DepositStatus.*;

/** Deposit lifecycle (§6.2). Every state change is validated against the source state. */
@Service
public class DepositService {

    private final DepositRepository deposits;
    private final LedgerService ledger;
    private final PaymentService payments;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DepositService(DepositRepository deposits, LedgerService ledger, PaymentService payments,
                          OutboxService outbox, TransactionTemplate tx, Clock clock) {
        this.deposits = deposits;
        this.ledger = ledger;
        this.payments = payments;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DepositView create(UUID orderId, UUID renterId, long amount) {
        Deposit d = new Deposit(orderId, renterId, amount, clock.instant());
        deposits.save(d);
        return DepositView.of(d);
    }

    @Transactional(readOnly = true)
    public DepositView getByOrder(UUID orderId) {
        return deposits.findByOrderId(orderId).map(DepositView::of).orElseThrow(() -> ApiException.notFound("Tiền cọc"));
    }

    @Transactional(readOnly = true)
    public Page<DepositView> listForRenter(UUID renterId, Pageable pageable) {
        return deposits.findByRenterId(renterId, pageable).map(DepositView::of);
    }

    /** Payment captured: PENDING_PAYMENT → HELD. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markHeld(UUID orderId) {
        Deposit d = lock(orderId);
        require(d, PENDING_PAYMENT);
        move(d, HELD);
    }

    /** Order expired/cancelled before payment: PENDING_PAYMENT → VOID. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void voidDeposit(UUID orderId) {
        Deposit d = lock(orderId);
        if (d.getStatus() == PENDING_PAYMENT) {
            move(d, VOID);
        }
    }

    /**
     * Cancelled/rejected before the rental: HELD → REFUND_PENDING with no extra hold. The ledger part is in the
     * order's ORDER_CANCELLED_REFUND transaction. Returns the deposit amount to refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long markRefundOnCancel(UUID orderId) {
        Deposit d = lock(orderId);
        require(d, HELD);
        d.addRefunded(d.remaining());
        move(d, REFUND_PENDING);
        return d.getAmount();
    }

    /** Order ended normally: HELD → POST_RENTAL_HOLD until actual_end_at + deposit_hold_hours (R4). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void startPostRentalHold(UUID orderId, Instant holdUntil) {
        Deposit d = lock(orderId);
        if (d.getStatus() != HELD) {
            return; // already DISPUTED by an ACCOUNT_TAKEOVER report
        }
        d.setHoldUntil(holdUntil);
        move(d, POST_RENTAL_HOLD);
    }

    /** OWNER_DAMAGE / ACCOUNT_TAKEOVER opened: HELD|POST_RENTAL_HOLD → DISPUTED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DepositView markDisputed(UUID orderId, boolean requireBeforeHoldUntil) {
        Deposit d = lock(orderId);
        if (d.getStatus() == DISPUTED) {
            return DepositView.of(d);
        }
        if (d.getStatus() != HELD && d.getStatus() != POST_RENTAL_HOLD) {
            throw ApiException.unprocessable("DISPUTE_WINDOW_CLOSED", "Tiền cọc của đơn này không còn được giữ.");
        }
        if (requireBeforeHoldUntil && d.getStatus() == POST_RENTAL_HOLD
                && !clock.instant().isBefore(d.getHoldUntil())) {
            throw ApiException.unprocessable("DISPUTE_WINDOW_CLOSED", "Đã hết thời gian giữ cọc để khiếu nại.");
        }
        move(d, DISPUTED);
        return DepositView.of(d);
    }

    /**
     * Dispute decision on the deposit (§6.2): deduction x ≤ amount of this order's deposit only.
     * x > 0 → DEPOSIT_DEDUCTED to the seller, remainder refunded, SETTLED; x = 0 → full refund, REFUND_PENDING.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DepositView settleDispute(UUID orderId, long deduction, UUID sellerId, UUID decidedBy, String reference) {
        Deposit d = lock(orderId);
        require(d, DISPUTED);
        if (deduction < 0 || deduction > d.getAmount()) {
            throw ApiException.unprocessable("DEDUCTION_EXCEEDS_DEPOSIT", "Mức khấu trừ vượt quá tiền cọc của đơn.");
        }
        if (deduction > 0) {
            // The platform keeps its risk payout fee; the owner gets the rest.
            long fee = RiskPayoutFee.of(deduction);
            ledger.post(TxType.DEPOSIT_DEDUCTED, orderId, reference, "DEPOSIT_DEDUCTED:" + d.getId(), decidedBy,
                    List.of(Posting.debit(AccountCode.DEPOSIT_HELD, deduction),
                            Posting.credit(AccountCode.SELLER_AVAILABLE, sellerId, deduction - fee),
                            Posting.credit(AccountCode.PLATFORM_REVENUE, fee)));
            d.addDeducted(deduction);
        }
        long rest = d.remaining();
        if (rest > 0) {
            requestRefund(d, rest, reference, decidedBy);
        }
        move(d, deduction > 0 ? SETTLED : REFUND_PENDING);
        return DepositView.of(d);
    }

    /** releaseDeposits job (§9): POST_RENTAL_HOLD past hold_until, no dispute → refund. */
    public int releaseDue(int batchSize) {
        int n = 0;
        for (UUID id : deposits.findReleasable(clock.instant(), batchSize)) {
            Boolean done = tx.execute(s -> deposits.lockForJob(id).map(d -> {
                if (d.getStatus() != POST_RENTAL_HOLD || d.getHoldUntil().isAfter(clock.instant())) {
                    return false;
                }
                requestRefund(d, d.remaining(), "deposit-release:" + d.getId(), null);
                move(d, REFUND_PENDING);
                return true;
            }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                n++;
            }
        }
        return n;
    }

    /** RefundCompleted (kind DEPOSIT) → REFUNDED when the deposit is waiting for it. */
    @EventListener
    public void onRefundCompleted(DomainEvent event) {
        if (!event.is("RefundCompleted") || !"DEPOSIT".equals(event.string("kind"))) {
            return;
        }
        deposits.findByOrderIdForUpdate(event.uuid("orderId")).ifPresent(d -> {
            if (d.getStatus() == REFUND_PENDING && d.remaining() == 0) {
                move(d, REFUNDED);
            }
        });
    }

    private void requestRefund(Deposit d, long amount, String reference, UUID actor) {
        ledger.post(TxType.DEPOSIT_REFUND_REQUESTED, d.getOrderId(), reference, "DEPOSIT_REFUND_REQUESTED:" + d.getId(),
                actor, List.of(Posting.debit(AccountCode.DEPOSIT_HELD, amount),
                        Posting.credit(AccountCode.REFUND_PAYABLE, amount)));
        d.addRefunded(amount);
        payments.requestRefund(d.getOrderId(), null, "DEPOSIT", amount, reference);
    }

    private Deposit lock(UUID orderId) {
        return deposits.findByOrderIdForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Tiền cọc"));
    }

    private static void require(Deposit d, DepositStatus expected) {
        if (d.getStatus() != expected) {
            throw ApiException.invalidState("Tiền cọc đang ở trạng thái " + d.getStatus() + ", cần " + expected);
        }
    }

    private void move(Deposit d, DepositStatus to) {
        DepositStatus from = d.getStatus();
        d.moveTo(to, clock.instant());
        Map<String, Object> payload = new HashMap<>();
        payload.put("depositId", d.getId());
        payload.put("orderId", d.getOrderId());
        payload.put("renterId", d.getRenterId());
        payload.put("from", from);
        payload.put("to", to);
        if (d.getHoldUntil() != null) {
            payload.put("holdUntil", d.getHoldUntil());
        }
        outbox.record("Deposit", d.getId(), "DepositStateChanged", payload);
    }
}
