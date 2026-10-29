package com.rentgg;

import com.rentgg.identity.application.UserSearchService;
import com.rentgg.identity.application.UserSearchService.Criteria;
import com.rentgg.identity.application.UserSearchService.UserHit;
import com.rentgg.order.application.OrderView;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Staff find a user with whatever they have: email, name, user id, or an order / listing id. */
class UserSearchIT extends IntegrationTest {

    @Autowired UserSearchService search;

    private List<UserHit> find(String q) {
        return search.search(new Criteria(q, null, null, null, null), 0, 50).content();
    }

    private static List<UUID> ids(List<UserHit> hits) {
        return hits.stream().map(UserHit::id).toList();
    }

    @Test
    void findsByEmailNameAndIdPrefix() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        UUID id = auth.register("Search." + tag + "@test.dev", "password-123456", "Nguyễn Văn Tìm " + tag,
                LocalDate.of(1990, 1, 1)).id();

        assertThat(ids(find("search." + tag))).containsExactly(id);
        assertThat(ids(find("SEARCH." + tag.toUpperCase()))).containsExactly(id);
        assertThat(ids(find("nguyen van tim " + tag))).containsExactly(id);
        assertThat(ids(find("Nguyễn Văn Tìm " + tag))).containsExactly(id);
        assertThat(ids(find(id.toString().substring(0, 8)))).contains(id);
        assertThat(ids(find(id.toString()))).first().isEqualTo(id);
        assertThat(find("search." + tag).get(0).matchedBy()).contains("EMAIL");
        assertThat(find(id.toString()).get(0).matchedBy()).contains("USER_ID");
        assertThat(ids(find("%"))).doesNotContain(id);
    }

    @Test
    void findsBothPartiesFromAnOrderIdAndTheOwnerFromAListingId() {
        UUID renter = user();
        Setup s = standardListing();
        OrderView o = paidOrder(renter, s, Duration.ofHours(2), 60);

        List<UserHit> byOrder = find(o.id().toString().substring(0, 8));
        assertThat(ids(byOrder)).contains(renter, s.ownerId());
        assertThat(byOrder.stream().filter(h -> h.id().equals(renter)).findFirst().orElseThrow().matchedBy())
                .contains("ORDER");
        assertThat(ids(find(s.listingId().toString()))).contains(s.ownerId()).doesNotContain(renter);
    }

    @Test
    void filtersByKycAndRole() {
        UUID finance = user("FINANCE");
        List<UserHit> fin = search.search(new Criteria("", null, null, null, "FINANCE"), 0, 100).content();
        assertThat(ids(fin)).contains(finance);
        assertThat(fin).allMatch(h -> h.roles().contains("FINANCE"));

        jdbc.update("update users set kyc_status = 'PENDING' where id = ?", finance);
        List<UserHit> pending = search.search(new Criteria("", null, "PENDING", null, null), 0, 100).content();
        assertThat(ids(pending)).contains(finance);
        assertThat(pending).allMatch(h -> h.kycStatus().equals("PENDING"));
    }

    @Test
    void staffWhoHandleUsersCanLookUpButCustomersCannot() throws Exception {
        UUID target = user();
        String email = users.get(target).email();
        for (String role : List.of("SUPPORT", "FINANCE", "ADMIN")) {
            mvc.perform(get("/api/v1/staff/users").param("q", email).header("Authorization", bearer(user(role))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(target.toString()));
        }
        mvc.perform(get("/api/v1/staff/users/brief").param("ids", target.toString())
                        .header("Authorization", bearer(user("FINANCE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(email));
        mvc.perform(get("/api/v1/staff/users").param("q", email).header("Authorization", bearer(user())))
                .andExpect(status().isForbidden());
    }
}
