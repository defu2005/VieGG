package com.rentgg.ledger.api;

import com.rentgg.ledger.application.LedgerService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/ledger")
@PreAuthorize("hasAuthority('ledger:read')")
public class LedgerAdminController {

    private final LedgerService ledger;

    public LedgerAdminController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/reconciliation")
    public Map<String, Object> reconciliation() {
        return ledger.reconcile();
    }

    @GetMapping("/orders")
    public List<LedgerService.OrderSummary> searchOrders(@RequestParam(defaultValue = "") String q,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ledger.searchOrders(q, size);
    }

    @GetMapping("/orders/{orderId}")
    public List<LedgerService.TxView> orderTransactions(@PathVariable UUID orderId) {
        return ledger.transactionsForOrder(orderId);
    }
}
