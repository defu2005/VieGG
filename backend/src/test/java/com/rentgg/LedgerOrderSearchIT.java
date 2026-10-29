package com.rentgg;

import com.rentgg.ledger.application.LedgerService.OrderSummary;
import com.rentgg.order.application.OrderView;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Finance staff can find an order's id without access to order pages. */
class LedgerOrderSearchIT extends IntegrationTest {

    @Test
    void findsOrderByIdPrefixEmailAndTitle() {
        UUID renter = user();
        OrderView o = paidOrder(renter, standardListing(), Duration.ofHours(2), 60);
        String renterEmail = users.get(renter).email();

        assertThat(ids(ledger.searchOrders(o.id().toString().substring(0, 8), 20))).contains(o.id());
        assertThat(ids(ledger.searchOrders(o.id().toString().substring(0, 8).toUpperCase(), 20))).contains(o.id());
        assertThat(ids(ledger.searchOrders(renterEmail, 20))).containsExactly(o.id());
        assertThat(ids(ledger.searchOrders(users.get(o.ownerId()).email(), 20))).containsExactly(o.id());
        assertThat(ids(ledger.searchOrders("", 20))).contains(o.id());
        assertThat(ids(ledger.searchOrders("%", 20))).doesNotContain(o.id());

        OrderSummary s = ledger.searchOrders(renterEmail, 20).get(0);
        assertThat(s.renterEmail()).isEqualTo(renterEmail);
        assertThat(s.transactionCount()).isPositive();
        assertThat(ids(ledger.searchOrders(s.listingTitle(), 50))).contains(o.id());
    }

    @Test
    void financeCanSearchButUsersCannot() throws Exception {
        UUID renter = user();
        OrderView o = paidOrder(renter, standardListing(), Duration.ofHours(2), 60);
        String q = o.id().toString().substring(0, 8);

        mvc.perform(get("/api/v1/admin/ledger/orders").param("q", q).header("Authorization", bearer(user("FINANCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(o.id().toString()));
        mvc.perform(get("/api/v1/admin/ledger/orders").param("q", q).header("Authorization", bearer(renter)))
                .andExpect(status().isForbidden());
    }

    private static List<UUID> ids(List<OrderSummary> rows) {
        return rows.stream().map(OrderSummary::id).toList();
    }
}
