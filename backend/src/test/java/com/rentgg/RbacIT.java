package com.rentgg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.approval.domain.ApprovalRequest.ActionType;
import com.rentgg.identity.application.AuthorityService;
import com.rentgg.identity.application.RbacService;
import com.rentgg.shared.security.Permission;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RBAC: legacy role behaviour is preserved, and roles/permissions can be changed at runtime safely. */
class RbacIT extends IntegrationTest {

    private static final List<String> ROLES = List.of("USER", "SUPPORT", "ADMIN", "FINANCE");

    @Autowired private AuthorityService authorities;
    @Autowired private RbacService rbac;
    @Autowired private ObjectMapper mapper;

    // ================================================================= behaviour of the 4 original roles

    /** Same allow/deny matrix as the hard-coded hasRole / URL rules before RBAC. */
    @Test
    void legacyEndpointMatrixIsUnchanged() throws Exception {
        UUID target = user();
        String rnd = UUID.randomUUID().toString();
        record Case(String name, Req req, Set<String> allowed) {}
        List<Case> cases = List.of(
                new Case("user detail", () -> get("/api/v1/admin/users/" + target), Set.of("ADMIN", "SUPPORT")),
                new Case("abuse signals", () -> get("/api/v1/admin/users/" + target + "/abuse-signals"), Set.of("ADMIN", "SUPPORT")),
                new Case("kyc decide", () -> json(post("/api/v1/admin/users/" + target + "/kyc"), "{\"decision\":\"REJECT\"}"), Set.of("ADMIN")),
                new Case("listing queue", () -> get("/api/v1/admin/listings"), Set.of("ADMIN", "SUPPORT")),
                new Case("listing approve", () -> post("/api/v1/admin/listings/" + rnd + "/approve"), Set.of("ADMIN")),
                new Case("listing reject", () -> json(post("/api/v1/admin/listings/" + rnd + "/reject"), "{\"note\":\"x\"}"), Set.of("ADMIN")),
                new Case("ledger", () -> get("/api/v1/admin/ledger/reconciliation"), Set.of("ADMIN", "FINANCE")),
                new Case("dispute queue", () -> get("/api/v1/support/disputes"), Set.of("ADMIN", "SUPPORT")),
                new Case("withdrawal queue", () -> get("/api/v1/finance/withdrawals"), Set.of("ADMIN", "FINANCE")),
                new Case("mark paid", () -> json(post("/api/v1/finance/withdrawals/" + rnd + "/mark-paid"), "{\"bankRef\":\"B1\"}"), Set.of("FINANCE")),
                new Case("approvals list", () -> get("/api/v1/admin/approvals"), Set.of("ADMIN", "SUPPORT", "FINANCE")),
                new Case("pending accounts", () -> get("/api/v1/admin/game-accounts"), Set.of("ADMIN")),
                new Case("verify recovery", () -> post("/api/v1/admin/game-accounts/" + rnd + "/verify-recovery"), Set.of("ADMIN")),
                new Case("rbac roles", () -> get("/api/v1/admin/rbac/roles"), Set.of("ADMIN")));
        for (String role : ROLES) {
            UUID u = "USER".equals(role) ? user() : user(role);
            String auth = bearer(u);
            for (Case c : cases) {
                int code = mvc.perform(c.req().build().header("Authorization", auth)).andReturn().getResponse().getStatus();
                if (c.allowed().contains(role)) {
                    assertThat(code).as(role + " → " + c.name()).isNotEqualTo(403).isNotEqualTo(401);
                } else {
                    assertThat(code).as(role + " → " + c.name()).isEqualTo(403);
                }
            }
        }
    }

    /** Same requester/approver table as the former REQUESTER_ROLES / APPROVER_ROLES maps. */
    @Test
    void legacyApprovalMatrixIsUnchanged() {
        Map<ActionType, Set<String>> requesters = Map.of(
                ActionType.GAME_ALLOW, Set.of("ADMIN"),
                ActionType.ACCOUNT_SECURITY_RESTORE, Set.of("SUPPORT", "ADMIN"),
                ActionType.SELLER_HOLD_EARLY_RELEASE, Set.of("SUPPORT", "FINANCE", "ADMIN"),
                ActionType.LEDGER_ADJUSTMENT, Set.of("FINANCE"));
        Map<ActionType, Set<String>> approvers = Map.of(
                ActionType.GAME_ALLOW, Set.of("ADMIN"),
                ActionType.ACCOUNT_SECURITY_RESTORE, Set.of("ADMIN"),
                ActionType.SELLER_HOLD_EARLY_RELEASE, Set.of("FINANCE", "ADMIN"),
                ActionType.LEDGER_ADJUSTMENT, Set.of("FINANCE", "ADMIN"));
        for (String role : ROLES) {
            Set<String> perms = authorities.fresh("USER".equals(role) ? user() : user(role)).permissions();
            for (ActionType t : ActionType.values()) {
                assertThat(perms.contains(Permission.approvalRequest(t.name()))).as(role + " request " + t)
                        .isEqualTo(requesters.get(t).contains(role));
                assertThat(perms.contains(Permission.approvalDecide(t.name()))).as(role + " decide " + t)
                        .isEqualTo(approvers.get(t).contains(role));
            }
        }
    }

    @Test
    void meReturnsPermissions() throws Exception {
        UUID u = user("SUPPORT");
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value(org.hamcrest.Matchers.containsInAnyOrder("USER", "SUPPORT")))
                .andExpect(jsonPath("$.staff").value(true))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("dispute:resolve")))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("listing:approve"))));
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(user())))
                .andExpect(jsonPath("$.staff").value(false))
                .andExpect(jsonPath("$.permissions").value(org.hamcrest.Matchers.hasItem("order:place")));
    }

    /** Staff accounts keep the base role USER but get none of its customer permissions. */
    @Test
    void staffAccountsHaveNoCustomerFeatures() throws Exception {
        Set<String> customer = authorities.fresh(user()).permissions();
        assertThat(customer).contains("order:place", "listing:manage_own", "wallet:withdraw");
        for (String role : List.of("SUPPORT", "ADMIN", "FINANCE")) {
            var a = authorities.fresh(user(role));
            assertThat(a.staff()).as(role).isTrue();
            assertThat(a.roles()).as(role).contains("USER");
            assertThat(a.permissions()).as(role).doesNotContainAnyElementsOf(customer);
        }
        String staff = bearer(user("SUPPORT"));
        mvc.perform(post("/api/v1/listings/" + UUID.randomUUID() + "/pause").header("Authorization", staff))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/orders/" + UUID.randomUUID() + "/cancel").header("Authorization", staff))
                .andExpect(status().isForbidden());

        // USER can never become a staff role; an admin may still edit its customer permissions.
        UUID admin = user("ADMIN");
        mvc.perform(json(put("/api/v1/admin/rbac/roles/USER"), Map.of("name", "Người dùng", "staff", true))
                .header("Authorization", bearer(admin))).andExpect(status().isUnprocessableEntity());
        RbacService.RoleView base = rbac.getRole("USER");
        assertThat(base.staff()).isFalse();
        rbac.setPermissions(admin, "USER", base.permissions());
    }

    // ================================================================= runtime changes

    /** A new role works immediately, and revoking it takes effect on the next request with the same token. */
    @Test
    void customRoleGrantAndImmediateRevoke() throws Exception {
        UUID admin = user("ADMIN");
        UUID mod = user();
        String code = "MOD_" + suffix();
        String token = bearer(mod);

        mvc.perform(get("/api/v1/admin/listings").header("Authorization", token)).andExpect(status().isForbidden());

        mvc.perform(json(post("/api/v1/admin/rbac/roles"), Map.of("code", code, "name", "Moderator",
                        "permissions", List.of("listing:review_queue", "listing:read_any")))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.system").value(false));
        mvc.perform(json(post("/api/v1/admin/rbac/users/" + mod + "/roles"), Map.of("role", code))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/listings").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/listings/" + UUID.randomUUID() + "/approve").header("Authorization", token))
                .andExpect(status().isForbidden());

        // Add a permission to the role: effective at once for every holder.
        mvc.perform(json(put("/api/v1/admin/rbac/roles/" + code + "/permissions"),
                        Map.of("permissions", List.of("listing:review_queue", "listing:read_any", "listing:approve")))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/listings/" + UUID.randomUUID() + "/approve").header("Authorization", token))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/v1/admin/rbac/users/" + mod + "/roles/" + code).header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/listings").header("Authorization", token)).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("""
                select count(*) from audit_logs where actor_id = ? and action in
                ('ROLE_CREATED','USER_ROLE_GRANTED','ROLE_PERMISSIONS_CHANGED','USER_ROLE_REVOKED')""",
                Integer.class, admin)).isEqualTo(4);

        // Unused custom role can be deleted.
        mvc.perform(delete("/api/v1/admin/rbac/roles/" + code).header("Authorization", bearer(admin)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from roles where code = ?", Integer.class, code)).isZero();
    }

    @Test
    void noPrivilegeEscalation() throws Exception {
        UUID admin = user("ADMIN");
        // ADMIN does not hold withdrawal:mark_paid, so it cannot hand it out.
        mvc.perform(json(post("/api/v1/admin/rbac/roles"), Map.of("code", "PAYER_" + suffix(), "name", "Payer",
                        "permissions", List.of("withdrawal:mark_paid")))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mvc.perform(json(put("/api/v1/admin/rbac/roles/SUPPORT/permissions"),
                        Map.of("permissions", List.of("withdrawal:mark_paid")))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));

        // Cannot change own roles.
        mvc.perform(json(post("/api/v1/admin/rbac/users/" + admin + "/roles"), Map.of("role", "FINANCE"))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_ROLE_CHANGE_FORBIDDEN"));

        // Without role:manage / role:assign nothing is allowed.
        UUID support = user("SUPPORT");
        mvc.perform(json(post("/api/v1/admin/rbac/users/" + user() + "/roles"), Map.of("role", "ADMIN"))
                        .header("Authorization", bearer(support)))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/admin/rbac/roles"), Map.of("code", "X_" + suffix(), "name", "X"))
                        .header("Authorization", bearer(support)))
                .andExpect(status().isForbidden());
    }

    @Test
    void guardRails() throws Exception {
        UUID admin = user("ADMIN");
        String auth = bearer(admin);

        mvc.perform(delete("/api/v1/admin/rbac/roles/SUPPORT").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SYSTEM_ROLE"));
        mvc.perform(delete("/api/v1/admin/rbac/users/" + user() + "/roles/USER").header("Authorization", auth))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("BASE_ROLE_REQUIRED"));
        mvc.perform(json(post("/api/v1/admin/rbac/roles"), Map.of("code", "BAD_" + suffix(), "name", "Bad",
                        "permissions", List.of("listing:fly")))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
        mvc.perform(json(post("/api/v1/admin/rbac/roles"), Map.of("code", "lower-case", "name", "Bad"))
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());

        // Role still assigned → cannot delete.
        String code = "INUSE_" + suffix();
        rbac.createRole(admin, code, "In use", null, true, Set.of("user:read"));
        rbac.grantRole(admin, user(), code);
        mvc.perform(delete("/api/v1/admin/rbac/roles/" + code).header("Authorization", auth))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROLE_IN_USE"));

        // Lock-out: removing role:manage from ADMIN would leave nobody able to manage roles.
        Set<String> adminPerms = new java.util.HashSet<>(rbac.getRole("ADMIN").permissions());
        adminPerms.remove("role:manage");
        mvc.perform(json(put("/api/v1/admin/rbac/roles/ADMIN/permissions"), Map.of("permissions", adminPerms))
                        .header("Authorization", auth))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMIN"));
        assertThat(rbac.getRole("ADMIN").permissions()).contains("role:manage");
    }

    @Test
    void listsPermissionsAndRoles() throws Exception {
        String auth = bearer(user("ADMIN"));
        String body = mvc.perform(get("/api/v1/admin/rbac/permissions").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).size()).isEqualTo(Permission.values().length);
        JsonNode roles = mapper.readTree(mvc.perform(get("/api/v1/admin/rbac/roles").header("Authorization", auth))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(roles.findValuesAsText("code")).contains("USER", "SUPPORT", "ADMIN", "FINANCE");
    }

    /** Ownership rules still apply on top of permissions: a plain user cannot read someone else's order. */
    @Test
    void ownershipStillEnforced() throws Exception {
        Setup s = standardListing();
        UUID renter = user();
        var o = order(renter, s, java.time.Duration.ofHours(3), 60);
        mvc.perform(get("/api/v1/orders/" + o.id()).header("Authorization", bearer(user()))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/orders/" + o.id()).header("Authorization", bearer(renter))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + o.id()).header("Authorization", bearer(user("SUPPORT")))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/" + o.id()).header("Authorization", bearer(user("FINANCE")))).andExpect(status().isNotFound());
    }

    // ================================================================= helpers

    @FunctionalInterface
    private interface Req {
        MockHttpServletRequestBuilder build() throws Exception;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, Object body) throws Exception {
        String content = body instanceof String s ? s : mapper.writeValueAsString(body);
        return b.contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }
}
