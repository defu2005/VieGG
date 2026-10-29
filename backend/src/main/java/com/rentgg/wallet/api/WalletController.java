package com.rentgg.wallet.api;

import com.rentgg.deposit.application.DepositService;
import com.rentgg.deposit.application.DepositView;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.security.Permission;
import com.rentgg.shared.web.PageResponse;
import com.rentgg.shared.web.Paging;
import com.rentgg.wallet.application.WalletService;
import com.rentgg.wallet.domain.Withdrawal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class WalletController {

    private final WalletService wallet;
    private final DepositService deposits;
    private final PaymentService payments;
    private final LedgerService ledger;

    public WalletController(WalletService wallet, DepositService deposits, PaymentService payments, LedgerService ledger) {
        this.wallet = wallet;
        this.deposits = deposits;
        this.payments = payments;
        this.ledger = ledger;
    }

    /** {@code available} is the wallet balance, for renters and owners alike. */
    @GetMapping("/wallet/balances")
    public WalletService.Balances balances() {
        return wallet.balances(CurrentUser.id());
    }

    /** Starts a top-up: returns the PSP checkout; the wallet is credited when the PSP confirms the payment. */
    @PostMapping("/wallet/topups")
    @PreAuthorize("hasAuthority('wallet:withdraw')")
    public ResponseEntity<TopupDto> topup(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                          @Valid @RequestBody TopupRequest req,
                                          jakarta.servlet.http.HttpServletRequest http) {
        var intent = payments.createTopup(CurrentUser.id(), req.amount(), idempotencyKey, req.provider(), http.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(TopupDto.of(intent));
    }

    @GetMapping("/wallet/topups")
    public java.util.List<TopupDto> myTopups(@RequestParam(defaultValue = "20") int size) {
        return payments.recentTopups(CurrentUser.id(), Math.max(1, Math.min(size, 100))).stream()
                .map(TopupDto::of).toList();
    }

    /** Wallet statement: top-ups, payments, refunds, revenue, withdrawals. */
    @GetMapping("/wallet/transactions")
    public PageResponse<LedgerService.WalletEntry> myTransactions(@RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return ledger.walletHistory(CurrentUser.id(), p, s);
    }

    /** {@code provider}: code from GET /payments/providers; empty → the default provider. */
    public record TopupRequest(@Positive long amount, @Size(max = 20) String provider) {}

    public record TopupDto(UUID id, long amount, String provider, String status, String checkoutUrl, Instant createdAt) {
        static TopupDto of(PaymentService.IntentView i) {
            return new TopupDto(i.id(), i.amount(), i.provider(), i.status(), i.checkoutUrl(), i.createdAt());
        }
    }

    /** Renter's deposits: one line per order (no shared deposit balance, R5). */
    @GetMapping("/wallet/deposits")
    public PageResponse<DepositView> myDeposits(@RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(deposits.listForRenter(CurrentUser.id(), pageable), x -> x);
    }

    @PostMapping("/wallet/withdrawals")
    @PreAuthorize("hasAuthority('wallet:withdraw')")
    public ResponseEntity<WithdrawalDto> withdraw(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @Valid @RequestBody WithdrawRequest req) {
        WalletService.WithdrawalResult r = wallet.requestWithdrawal(CurrentUser.id(), req.amount(),
                new WalletService.BankAccount(req.bankName(), req.bankAccountNumber(), req.bankAccountName()), idempotencyKey);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(WithdrawalDto.of(r.withdrawal(), false));
    }

    @GetMapping("/wallet/withdrawals")
    public PageResponse<WithdrawalDto> myWithdrawals(@RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(wallet.listMine(CurrentUser.id(), pageable), w -> WithdrawalDto.of(w, false));
    }

    @GetMapping("/finance/withdrawals")
    @PreAuthorize("hasAuthority('withdrawal:list_all')")
    public PageResponse<WithdrawalDto> financeList(@RequestParam(required = false) String status,
                                                   @RequestParam(required = false) UUID ownerId,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.by(Sort.Direction.ASC, "createdAt"));
        boolean full = CurrentUser.has(Permission.WITHDRAWAL_READ_BANK_DETAILS);
        return PageResponse.of(wallet.listByStatus(status, ownerId, pageable), w -> WithdrawalDto.of(w, full));
    }

    @PostMapping("/finance/withdrawals/{id}/mark-paid")
    @PreAuthorize("hasAuthority('withdrawal:mark_paid')")
    public WithdrawalDto markPaid(@PathVariable UUID id, @Valid @RequestBody MarkPaidRequest req) {
        return WithdrawalDto.of(wallet.markPaid(CurrentUser.id(), id, req.bankRef()), true);
    }

    @PostMapping("/finance/withdrawals/{id}/mark-failed")
    @PreAuthorize("hasAuthority('withdrawal:mark_failed')")
    public WithdrawalDto markFailed(@PathVariable UUID id, @Valid @RequestBody MarkFailedRequest req) {
        return WithdrawalDto.of(wallet.markFailed(CurrentUser.id(), id, req.reason()), true);
    }

    public record WithdrawRequest(@Positive long amount, @NotBlank @Size(max = 100) String bankName,
                                  @NotBlank @Pattern(regexp = "[0-9]{6,20}") String bankAccountNumber,
                                  @NotBlank @Size(max = 100) String bankAccountName) {
        @Override
        public String toString() {
            return "WithdrawRequest[amount=" + amount + ", bankAccountNumber=***]";
        }
    }

    public record MarkPaidRequest(@NotBlank String bankRef) {}

    public record MarkFailedRequest(@NotBlank String reason) {}

    public record WithdrawalDto(UUID id, UUID ownerId, long amount, String status, Map<String, Object> bank,
                                String bankRef, String failureReason, Instant processedAt, Instant createdAt) {
        /** Bank account number masked (last 4 digits) except for FINANCE (§12.10). */
        static WithdrawalDto of(Withdrawal w, boolean fullBank) {
            Map<String, Object> bank = new LinkedHashMap<>(w.getBankSnapshot());
            if (!fullBank) {
                String n = String.valueOf(bank.get("bankAccountNumber"));
                bank.put("bankAccountNumber", "****" + n.substring(Math.max(0, n.length() - 4)));
            }
            return new WithdrawalDto(w.getId(), w.getOwnerId(), w.getAmount(), w.getStatus(), bank, w.getBankRef(),
                    w.getFailureReason(), w.getProcessedAt(), w.getCreatedAt());
        }
    }
}
