package com.rentgg.support;

import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.catalog.domain.HandoverMode;
import com.rentgg.catalog.domain.TosStatus;
import com.rentgg.deposit.application.DepositService;
import com.rentgg.deposit.application.DepositView;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.identity.application.AuthService;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.application.UserView;
import com.rentgg.identity.domain.User;
import com.rentgg.identity.infrastructure.UserRepository;
import com.rentgg.jobs.Jobs;
import com.rentgg.ledger.application.LedgerService;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.order.application.OrderService;
import com.rentgg.order.application.OrderView;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.JwtService;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Base for integration tests on a real PostgreSQL 16 (exclusion constraints and triggers are part of the logic).
 * Uses TEST_DB_URL when set, otherwise Testcontainers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
public abstract class IntegrationTest {

    private static PostgreSQLContainer<?> container;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        String url = System.getenv("TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            r.add("spring.datasource.url", () -> url);
            r.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "rentgg"));
            r.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "rentgg_dev"));
            return;
        }
        synchronized (IntegrationTest.class) {
            if (container == null) {
                container = new PostgreSQLContainer<>("postgres:16");
                container.start();
            }
        }
        r.add("spring.datasource.url", container::getJdbcUrl);
        r.add("spring.datasource.username", container::getUsername);
        r.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null ? def : v;
    }

    @Autowired protected MutableClock clock;
    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected AuthService auth;
    @Autowired protected UserService users;
    @Autowired protected UserRepository userRepository;
    @Autowired protected JwtService jwt;
    @Autowired protected CatalogService catalog;
    @Autowired protected GameAccountService gameAccounts;
    @Autowired protected ListingService listings;
    @Autowired protected OrderService orders;
    @Autowired protected PaymentService payments;
    @Autowired protected DepositService deposits;
    @Autowired protected LedgerService ledger;
    @Autowired protected Jobs jobs;

    @BeforeEach
    void moveClockForward() {
        // Time only moves forward across tests; start each test at a fresh, whole hour.
        clock.set(clock.instant().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.HOURS));
    }

    // ------------------------------------------------------------------ fixtures

    protected UUID user(String... roles) {
        UUID id = auth.register("u" + UUID.randomUUID() + "@test.dev", "password-123456", "Test user",
                LocalDate.of(1995, 5, 5)).id();
        tx.executeWithoutResult(s -> {
            User u = userRepository.findById(id).orElseThrow();
            for (String r : roles) {
                u.addRole(r);
            }
        });
        return id;
    }

    /** A user acting as account owner (no KYC required). */
    protected UUID owner() {
        return user();
    }

    protected String bearer(UUID userId) {
        UserView u = users.get(userId);
        return "Bearer " + jwt.issueAccessToken(userId, u.roles(), u.status().name()).value();
    }

    protected UUID game(UnaryOperator<GamePolicy> customize) {
        GamePolicy p = customize.apply(GamePolicy.defaults("Game " + UUID.randomUUID(), 50_000, 2_000_000));
        UUID admin = user("ADMIN");
        UUID id = catalog.create(admin, "g" + UUID.randomUUID().toString().substring(0, 12), p).id();
        catalog.changeTosStatus(admin, id, TosStatus.ALLOWED, null, null, null);
        return id;
    }

    protected UUID game() {
        return game(p -> p);
    }

    public record Setup(UUID gameId, UUID ownerId, UUID accountId, UUID listingId) {}

    protected Setup listing(long pricePerHour, long deposit, boolean autoAccept, EndMode endMode) {
        return listing(game(), pricePerHour, deposit, autoAccept, endMode);
    }

    protected Setup listing(UUID gameId, long pricePerHour, long deposit, boolean autoAccept, EndMode endMode) {
        UUID owner = owner();
        UUID admin = user("ADMIN");
        UUID account = gameAccounts.create(owner, gameId, "VN", "player_" + owner.toString().substring(0, 6),
                "secret-pass-1", true, true, true).id();
        gameAccounts.verifyRecovery(admin, account);
        UUID listing = listings.create(owner, account, new ListingService.Content("Acc", "desc", java.util.Map.of(),
                pricePerHour, deposit, autoAccept, endMode)).id();
        listings.submit(owner, listing);
        listings.approve(admin, listing);
        return new Setup(gameId, owner, account, listing);
    }

    /** Standard setup of §7.4: 30.000đ/h, deposit 200.000đ, auto-accept, FORCE_AT_END. */
    protected Setup standardListing() {
        return listing(30_000, 200_000, true, EndMode.FORCE_AT_END);
    }

    protected OrderView order(UUID renter, Setup s, Duration startIn, int minutes) {
        return orders.create(renter, s.listingId(), clock.instant().plus(startIn), minutes, UUID.randomUUID().toString()).order();
    }

    /** Tops up the renter's wallet with exactly the amount due, then pays the order from the wallet. */
    protected OrderView pay(OrderView o) {
        topup(o.renterId(), o.amountToPay());
        orders.pay(o.renterId(), o.id(), UUID.randomUUID().toString());
        return orders.getInternal(o.id());
    }

    /** Wallet top-up through the fake PSP (checkout + simulated webhook). */
    protected void topup(UUID user, long amount) {
        var intent = payments.createTopup(user, amount, UUID.randomUUID().toString());
        payments.simulateSuccess(intent.id(), user);
    }

    protected long walletBalance(UUID user) {
        return ledger.liabilityBalance(com.rentgg.ledger.domain.AccountCode.SELLER_AVAILABLE, user);
    }

    protected OrderView paidOrder(UUID renter, Setup s, Duration startIn, int minutes) {
        return pay(order(renter, s, startIn, minutes));
    }

    protected OrderView reload(OrderView o) {
        return orders.getInternal(o.id());
    }

    protected DepositView deposit(OrderView o) {
        return deposits.getByOrder(o.id());
    }

    /** Moves the clock and runs every job once. */
    protected void advanceAndRun(Duration d) {
        clock.advance(d);
        jobs.runAll();
    }

    protected void runJobs() {
        jobs.runAll();
    }

    protected Instant now() {
        return clock.instant();
    }

    protected static void assertApi(ThrowableAssert.ThrowingCallable call, String code) {
        Throwable t = catchThrowable(call);
        assertThat(t).as("expected ApiException " + code).isInstanceOf(ApiException.class);
        assertThat(((ApiException) t).code()).isEqualTo(code);
    }

    protected long totalLedger() {
        return ledger.totalImbalance();
    }
}
