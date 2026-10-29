package com.rentgg.jobs;

import com.rentgg.approval.application.ApprovalService;
import com.rentgg.approval.domain.ApprovalRequest.ActionType;
import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.application.GameView;
import com.rentgg.catalog.domain.AccountKeyRule;
import com.rentgg.catalog.domain.AccountKeyType;
import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.catalog.domain.GameRiskLevel;
import com.rentgg.catalog.domain.HandoverMode;
import com.rentgg.catalog.domain.TosStatus;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.identity.application.AuthService;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.domain.RiskTier;
import com.rentgg.identity.domain.User;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.identity.infrastructure.UserRepository;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.order.application.OrderService;
import com.rentgg.order.application.OrderView;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.security.Roles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dev profile seed (§17.2). Covers every state that can be reached at startup with the real clock; states that
 * need time to pass (IN_RENTAL, ENDED, disputes, withdrawals) are reached automatically by the "quick" game
 * orders a few minutes after boot. See docs/RentGG_Dev_Seed.md.
 * All seeded users share the local-only password below; never used outside the dev profile.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    public static final String DEV_PASSWORD = "RentGG-dev-2026!";
    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final UserRepository userRepository;
    private final AuthService auth;
    private final UserService users;
    private final CatalogService catalog;
    private final GameAccountService gameAccounts;
    private final ListingService listings;
    private final OrderService orders;
    private final PaymentService payments;
    private final ApprovalService approvals;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DevDataSeeder(UserRepository userRepository, AuthService auth, UserService users, CatalogService catalog,
                         GameAccountService gameAccounts, ListingService listings, OrderService orders,
                         PaymentService payments, ApprovalService approvals, TransactionTemplate tx, Clock clock) {
        this.userRepository = userRepository;
        this.auth = auth;
        this.users = users;
        this.catalog = catalog;
        this.gameAccounts = gameAccounts;
        this.listings = listings;
        this.orders = orders;
        this.payments = payments;
        this.approvals = approvals;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByEmail("admin@rentgg.dev").isPresent()) {
            return;
        }
        // ---------------------------------------------------------------- users
        UUID admin = user("admin@rentgg.dev", "Admin 1", Roles.ADMIN);
        user("admin2@rentgg.dev", "Admin 2", Roles.ADMIN);
        UUID support = user("support@rentgg.dev", "Support", Roles.SUPPORT);
        user("finance@rentgg.dev", "Finance", Roles.FINANCE);
        UUID owner = user("owner@rentgg.dev", "Chủ account mẫu", null);
        UUID owner2 = user("owner2@rentgg.dev", "Chủ account rủi ro cao", null);
        UUID renter = user("renter@rentgg.dev", "Người thuê mẫu", null);
        UUID renter2 = user("renter2@rentgg.dev", "Người thuê 2", null);
        UUID restricted = user("restricted@rentgg.dev", "User bị hạn chế", null);
        UUID suspended = user("suspended@rentgg.dev", "User bị đình chỉ", null);
        users.changeStatus(admin, restricted, UserStatus.RESTRICTED, "DEV_SEED");
        users.changeStatus(admin, suspended, UserStatus.SUSPENDED, "DEV_SEED");
        users.setRiskTier(admin, owner2, RiskTier.HIGH, "DEV_SEED");

        // ---------------------------------------------------------------- games
        UUID demo = game(admin, "demo-game", GamePolicy.defaults("Game mẫu (dev)", 50_000, 2_000_000)
                .withRisk(GameRiskLevel.HIGH, "Account có nhiều trang phục giá trị, dễ bị đổi email khôi phục hoặc bán vật phẩm. "
                        + "Nên đặt cọc cao và chỉ cho thuê khi đã bật xác thực 2 lớp.")
                .withAccountKey(new AccountKeyRule(AccountKeyType.NUMERIC_ID, "UID", "800123456", false)),
                TosStatus.ALLOWED);
        GamePolicy d = GamePolicy.defaults("Game demo nhanh (dev)", 20_000, 500_000);
        // Short durations and no seller hold so the full lifecycle plays out a few minutes after boot.
        UUID quick = game(admin, "quick-game", new GamePolicy(d.name(), "VieGG Dev", null, HandoverMode.STATIC_CREDENTIAL,
                false, d.depositMin(), d.depositMax(), 1, 0, 0, 30, 5, 240, 2, 1000, 0, 5000, 0, 18,
                "Chỉ dùng cho dev: thời lượng ngắn, không giữ doanh thu", GameRiskLevel.LOW), TosStatus.ALLOWED);
        game(admin, "review-game", GamePolicy.defaults("Game chờ thẩm định ToS (dev)", 50_000, 1_000_000), null);
        UUID banned = game(admin, "banned-game", GamePolicy.defaults("Game bị cấm (dev)", 50_000, 1_000_000)
                .withRisk(GameRiskLevel.VERY_HIGH, "Nhà phát hành khóa vĩnh viễn account bị phát hiện cho thuê."), TosStatus.PROHIBITED);

        // ---------------------------------------------------------------- accounts & listings
        UUID lAuto = activeListing(admin, owner, demo, "demo_player_01", "Account mẫu rank Kim Cương",
                Map.of("rank", "Diamond", "skins", 120), 30_000, 200_000, true, EndMode.FORCE_AT_END);
        UUID lManual = activeListing(admin, owner, demo, "demo_player_02", "Account Cao Thủ (chủ duyệt đơn)",
                Map.of("rank", "Master"), 45_000, 300_000, false, EndMode.FORCE_AT_END);
        activeListing(admin, owner2, demo, "demo_player_03", "Account Thách Đấu (cho đánh nốt trận)",
                Map.of("rank", "Challenger"), 80_000, 500_000, true, EndMode.GRACE_FOR_CURRENT_MATCH);
        UUID lPaused = activeListing(admin, owner2, demo, "demo_player_04", "Account Bạch Kim (tạm dừng)",
                Map.of("rank", "Platinum"), 20_000, 100_000, true, EndMode.STAY_ONLINE_UNTIL_RECLAIM);
        listings.pause(owner2, lPaused);

        UUID aPending = account(owner, demo, "demo_player_05", true);
        UUID lPending = listings.create(owner, aPending, content("Account Vàng (chờ duyệt)", 25_000, 100_000, true)).id();
        listings.submit(owner, lPending);

        UUID aRejected = account(owner, demo, "demo_player_06", true);
        UUID lRejected = listings.create(owner, aRejected, content("Account mô tả sai (bị từ chối)", 25_000, 100_000, true)).id();
        listings.submit(owner, lRejected);
        listings.reject(admin, lRejected, "Ảnh và mô tả không khớp rank thực tế");

        // Recovery not verified yet → shows in the verify-recovery queue; listing stays DRAFT.
        UUID aUnverified = account(owner2, demo, "demo_player_07", false);
        listings.create(owner2, aUnverified, content("Account chưa xác minh khôi phục (nháp)", 30_000, 150_000, true));

        // Flagged + rotated → ready for an ACCOUNT_SECURITY_RESTORE approval.
        UUID aFlagged = account(owner2, demo, "demo_player_08", true);
        gameAccounts.flagSuspicious(support, aFlagged, "LOGIN_FROM_UNKNOWN_DEVICE");
        // Restore requires a credential created strictly after the suspension; clock ticks can be coarse.
        pause(Duration.ofMillis(50));
        gameAccounts.rotate(owner2, aFlagged, null, "demo-password-08-new", true);

        UUID lQuick1 = activeListing(admin, owner, quick, "quick_player_01", "Account demo nhanh 1",
                Map.of("note", "dev"), 60_000, 50_000, true, EndMode.FORCE_AT_END);
        UUID lQuick2 = activeListing(admin, owner2, quick, "quick_player_02", "Account demo nhanh 2",
                Map.of("note", "dev"), 60_000, 50_000, true, EndMode.FORCE_AT_END);

        // ---------------------------------------------------------------- orders
        Instant now = clock.instant();
        ZonedDateTime vnNow = now.atZone(UserService.VN).truncatedTo(ChronoUnit.HOURS);
        Instant tomorrow20h = vnNow.plusDays(1).withHour(20).toInstant();
        Instant inTwoDays20h = vnNow.plusDays(2).withHour(20).toInstant();
        Instant soonMinute = now.truncatedTo(ChronoUnit.MINUTES);

        OrderView confirmed = paid(order(renter, lAuto, tomorrow20h, 120));
        order(renter2, lAuto, inTwoDays20h, 60);                                   // PENDING_PAYMENT → EXPIRED after 15'
        OrderView awaiting = paid(order(renter, lManual, tomorrow20h, 60));          // AWAITING_OWNER_APPROVAL (30')
        OrderView rejected = paid(order(renter2, lManual, inTwoDays20h, 60));
        orders.reject(owner, rejected.id());
        // Starts in 90' (< renterFreeCancelMinutes) → late cancel: 50% rent kept in the seller review hold.
        OrderView lateCancel = paid(order(renter2, lAuto, soonMinute.plus(Duration.ofMinutes(90)), 60));
        orders.cancel(renter2, lateCancel.id());
        OrderView unpaidCancel = order(renter, lAuto, inTwoDays20h.plus(Duration.ofHours(2)), 60);
        orders.cancel(renter, unpaidCancel.id());
        // Quick game: IN_RENTAL in ~2', ENDED in ~7' (short) / ~32' (long), picked up by the minute jobs.
        OrderView quickShort = paid(order(renter, lQuick1, soonMinute.plus(Duration.ofMinutes(2)), 5));
        OrderView quickLong = paid(order(renter2, lQuick2, soonMinute.plus(Duration.ofMinutes(2)), 30));

        // ---------------------------------------------------------------- pending dual approvals
        approvals.create(admin, ActionType.GAME_ALLOW,
                Map.of("gameId", banned.toString(), "tosUrl", "https://example.com/tos-v2",
                        "reviewNote", "NPH đã cập nhật ToS cho phép cho thuê"), "TOS_UPDATED", "seed-game-allow");
        approvals.create(support, ActionType.ACCOUNT_SECURITY_RESTORE,
                Map.of("gameAccountId", aFlagged.toString()), "OWNER_ROTATED_PASSWORD", "seed-account-restore");
        approvals.create(support, ActionType.SELLER_HOLD_EARLY_RELEASE,
                Map.of("orderId", lateCancel.id().toString()), "TRUSTED_SELLER", "seed-hold-release");

        log.info("Dev seed created. Users *@rentgg.dev (password {}). Orders: confirmed={} awaiting={} quickShort={} "
                        + "quickLong={}. See docs/RentGG_Dev_Seed.md", DEV_PASSWORD, confirmed.id(), awaiting.id(),
                quickShort.id(), quickLong.id());
    }

    private UUID user(String email, String name, String role) {
        UUID id = auth.register(email, DEV_PASSWORD, name, LocalDate.of(1995, 1, 1)).id();
        if (role != null) {
            tx.executeWithoutResult(s -> {
                User u = userRepository.findById(id).orElseThrow();
                u.addRole(role);
            });
        }
        return id;
    }

    /** New games start as REVIEW_REQUIRED; {@code status == null} keeps that. */
    private UUID game(UUID admin, String code, GamePolicy policy, TosStatus status) {
        GameView g = catalog.create(admin, code, policy);
        if (status != null) {
            catalog.changeTosStatus(admin, g.id(), status, "https://example.com/tos/" + code, "dev seed", null);
        }
        return g.id();
    }

    private UUID account(UUID owner, UUID gameId, String username, boolean verifyRecovery) {
        // A stable fake UID per username; ignored by games identified by login.
        String uid = String.valueOf(800_000_000L + Math.floorMod(username.hashCode(), 100_000_000));
        UUID id = gameAccounts.create(owner, gameId, "VN", username, "demo-password-" + username, true, true, true,
                uid).id();
        if (verifyRecovery) {
            UUID admin = userRepository.findByEmail("admin@rentgg.dev").orElseThrow().getId();
            gameAccounts.verifyRecovery(admin, id);
        }
        return id;
    }

    private static ListingService.Content content(String title, long pricePerHour, long deposit, boolean autoAccept) {
        return new ListingService.Content(title, "Listing mẫu cho môi trường dev", Map.of(), pricePerHour, deposit,
                autoAccept, EndMode.FORCE_AT_END);
    }

    private UUID activeListing(UUID admin, UUID owner, UUID gameId, String username, String title,
                               Map<String, Object> attributes, long pricePerHour, long deposit, boolean autoAccept,
                               EndMode endMode) {
        UUID accountId = account(owner, gameId, username, true);
        UUID id = listings.create(owner, accountId, new ListingService.Content(title, "Listing mẫu cho môi trường dev",
                attributes, pricePerHour, deposit, autoAccept, endMode)).id();
        listings.submit(owner, id);
        listings.approve(admin, id);
        return id;
    }

    private OrderView order(UUID renter, UUID listingId, Instant startAt, int minutes) {
        return orders.create(renter, listingId, startAt, minutes, UUID.randomUUID().toString()).order();
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Pays through the fake PSP, same path as the dev simulate-success endpoint. */
    private OrderView paid(OrderView o) {
        var intent = orders.pay(o.renterId(), o.id(), UUID.randomUUID().toString());
        payments.simulateSuccess(intent.id(), o.renterId());
        return orders.getInternal(o.id());
    }
}
