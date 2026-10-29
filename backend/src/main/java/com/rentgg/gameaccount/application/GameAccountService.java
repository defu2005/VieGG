package com.rentgg.gameaccount.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.audit.application.AuditService;
import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.domain.AccountKeyRule;
import com.rentgg.config.RentGgProperties;
import com.rentgg.gameaccount.domain.CredentialVersion;
import com.rentgg.gameaccount.domain.GameAccount;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.gameaccount.infrastructure.CredentialVersionRepository;
import com.rentgg.gameaccount.infrastructure.GameAccountRepository;
import com.rentgg.identity.application.AbuseSignalService;
import com.rentgg.identity.application.UserService;
import com.rentgg.shared.crypto.CredentialCipher;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.error.DbErrors;
import com.rentgg.shared.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.rentgg.gameaccount.domain.SecurityState.*;

@Service
public class GameAccountService {

    private static final Logger log = LoggerFactory.getLogger(GameAccountService.class);

    private final GameAccountRepository accounts;
    private final CredentialVersionRepository credentials;
    private final CredentialCipher cipher;
    private final UserService users;
    private final CatalogService catalog;
    private final AbuseSignalService signals;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final RentGgProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    public GameAccountService(GameAccountRepository accounts, CredentialVersionRepository credentials,
                              CredentialCipher cipher, UserService users, CatalogService catalog,
                              AbuseSignalService signals, AuditService audit, OutboxService outbox,
                              ObjectMapper mapper, RentGgProperties props, TransactionTemplate tx, Clock clock) {
        this.accounts = accounts;
        this.credentials = credentials;
        this.cipher = cipher;
        this.users = users;
        this.catalog = catalog;
        this.signals = signals;
        this.audit = audit;
        this.outbox = outbox;
        this.mapper = mapper;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- owner actions

    /** UC-04 step 1 / UC-44: credentials + three recovery attestations → PENDING_VERIFICATION. */
    @Transactional
    public GameAccountView create(UUID ownerId, UUID gameId, String serverRegion, String username, String password,
                                  boolean recoveryEmailOwned, boolean recoveryPhoneOwned, boolean twoFactorOwned) {
        return create(ownerId, gameId, serverRegion, username, password, recoveryEmailOwned, recoveryPhoneOwned,
                twoFactorOwned, null);
    }

    /**
     * {@code accountKey}: the in-game identifier the game's rule asks for (UID, UUID, name...). Required unless the
     * game is identified by login. One identifier = one account on the whole platform, whoever owns it.
     */
    @Transactional
    public GameAccountView create(UUID ownerId, UUID gameId, String serverRegion, String username, String password,
                                  boolean recoveryEmailOwned, boolean recoveryPhoneOwned, boolean twoFactorOwned,
                                  String accountKey) {
        if (!(recoveryEmailOwned && recoveryPhoneOwned && twoFactorOwned)) {
            throw ApiException.unprocessable("RECOVERY_ATTESTATION_REQUIRED",
                    "Bạn phải xác nhận đang nắm giữ email, số điện thoại và 2FA khôi phục của account.");
        }
        users.requireEligible(ownerId, 18, false);
        AccountKeyRule rule = catalog.get(gameId).accountKey(); // game must exist
        Key key = rule.usesLogin() ? null : resolveKey(rule, serverRegion, accountKey);
        if (key != null) {
            requireKeyFree(ownerId, gameId, key, null);
        }
        String fingerprint = cipher.loginFingerprint(gameId, username);
        requireLoginFree(ownerId, gameId, fingerprint, null);
        Instant now = clock.instant();
        GameAccount a = new GameAccount(ownerId, gameId, serverRegion, maskUsername(username), now);
        a.setLoginFingerprint(fingerprint);
        if (key != null) {
            a.setAccountKey(key.value(), key.display(), key.scope(), now);
        }
        saveMappingDuplicates(a);
        CredentialVersion cv = encrypt(a.getId(), 1, username, password, now);
        credentials.save(cv);
        audit.record(ownerId, "CREDENTIAL_CREATED", "GameAccount", a.getId(), null,
                Map.of("credentialVersion", 1, "securityState", PENDING_VERIFICATION.name()), null);
        // Tells admins there is an account waiting for recovery verification.
        outbox.record("GameAccount", a.getId(), "GameAccountPendingVerification",
                Map.of("gameAccountId", a.getId(), "gameId", gameId));
        return GameAccountView.of(a, 1);
    }

    @Transactional(readOnly = true)
    public List<GameAccountView> listByOwner(UUID ownerId) {
        return accounts.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
                .map(a -> GameAccountView.of(a, activeVersion(a.getId()))).toList();
    }

    /**
     * Paged owner accounts, newest first. {@code unlisted} keeps only accounts without a listing;
     * non-empty {@code ids} keeps only those accounts.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<GameAccountView> listByOwner(UUID ownerId, boolean unlisted,
                                                                             java.util.Collection<UUID> ids, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(page, size, org.springframework.data.domain.Sort.by(
                org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id"));
        var found = ids != null && !ids.isEmpty() ? accounts.findByOwnerIdAndIdIn(ownerId, ids, pageable)
                : unlisted ? accounts.findUnlistedByOwner(ownerId, pageable)
                : accounts.findByOwnerId(ownerId, pageable);
        return found
                .map(a -> GameAccountView.of(a, activeVersion(a.getId())));
    }

    /** Paged admin queue, oldest first. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<GameAccountView> listByState(SecurityState state, int page, int size) {
        return accounts.findBySecurityState(state, org.springframework.data.domain.PageRequest.of(page, size,
                        org.springframework.data.domain.Sort.by("createdAt", "id")))
                .map(a -> GameAccountView.of(a, activeVersion(a.getId())));
    }

    /** Admin queue: accounts in one security state, oldest first. */
    @Transactional(readOnly = true)
    public List<GameAccountView> listByState(SecurityState state) {
        return accounts.findBySecurityStateOrderByCreatedAt(state).stream()
                .map(a -> GameAccountView.of(a, activeVersion(a.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public GameAccountView get(UUID accountId) {
        GameAccount a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Game account"));
        return GameAccountView.of(a, activeVersion(accountId));
    }

    @Transactional(readOnly = true)
    public GameAccountView getOwned(UUID ownerId, UUID accountId) {
        GameAccountView v = get(accountId);
        if (!v.ownerId().equals(ownerId)) {
            throw ApiException.notFound("Game account");
        }
        return v;
    }

    /** Like {@link #getOwned} but row-locks the account until the transaction ends (serializes per-account writes). */
    @Transactional
    public GameAccountView getOwnedForUpdate(UUID ownerId, UUID accountId) {
        GameAccount a = lockOwned(ownerId, accountId);
        return GameAccountView.of(a, activeVersion(accountId));
    }

    /**
     * UC-39: the owner changed the password on the publisher's side and submits it here.
     * ROTATION_PENDING/OVERDUE/AWAITING_RECLAIM/AVAILABLE → AVAILABLE; SUSPENDED_SECURITY_REVIEW only gets a new
     * credential version (restore needs dual approval).
     */
    @Transactional
    public GameAccountView rotate(UUID ownerId, UUID accountId, String newUsername, String newPassword,
                                  boolean loggedOutAllDevices) {
        if (!loggedOutAllDevices) {
            throw ApiException.unprocessable("LOGOUT_ALL_DEVICES_REQUIRED",
                    "Bạn cần đăng xuất khỏi mọi thiết bị trên game trước khi cập nhật mật khẩu.");
        }
        GameAccount a = lockOwned(ownerId, accountId);
        SecurityState from = a.getSecurityState();
        if (!Set.of(ROTATION_PENDING, ROTATION_OVERDUE, AWAITING_RECLAIM, AVAILABLE, SUSPENDED_SECURITY_REVIEW).contains(from)) {
            throw ApiException.invalidState("Không thể đổi mật khẩu khi account đang ở trạng thái " + from);
        }
        // Renters have seen every earlier password, so going back to one would hand the account to them again.
        if (usedPasswords(accountId).contains(newPassword)) {
            throw ApiException.unprocessable("PASSWORD_REUSED",
                    "Mật khẩu này account đã từng dùng. Hãy đặt mật khẩu mới chưa dùng bao giờ (có thể dùng “Gợi ý mật khẩu”).");
        }
        Instant now = clock.instant();
        CredentialVersion current = credentials.findActive(accountId).orElseThrow();
        String currentUsername = decrypt(current).username();
        String username = newUsername == null || newUsername.isBlank() ? currentUsername : newUsername;
        boolean sameLogin = username.trim().equalsIgnoreCase(currentUsername.trim());
        // Only a changed login is checked against other accounts. An old duplicate account (left without a
        // fingerprint by the backfill) must still be able to change its password, or it stays locked forever.
        if (!sameLogin || a.getLoginFingerprint() != null) {
            String fingerprint = cipher.loginFingerprint(a.getGameId(), username);
            if (!fingerprint.equals(a.getLoginFingerprint())) {
                requireLoginFree(ownerId, a.getGameId(), fingerprint, accountId);
                a.setLoginFingerprint(fingerprint);
            }
        }
        current.retire(now);
        credentials.saveAndFlush(current);
        int next = credentials.maxVersion(accountId) + 1;
        credentials.save(encrypt(accountId, next, username, newPassword, now));
        a.setLoginHint(maskUsername(username), now);
        if (from != SUSPENDED_SECURITY_REVIEW && from != AVAILABLE) {
            Duration took = Duration.between(a.getSecurityStateChangedAt(), now);
            changeState(a, AVAILABLE, "ROTATION_COMPLETED");
            audit.record(ownerId, "ROTATION_COMPLETED", "GameAccount", accountId, Map.of("securityState", from.name()),
                    Map.of("securityState", AVAILABLE.name(), "credentialVersion", next, "durationSeconds", took.toSeconds()), null);
        } else {
            audit.record(ownerId, "CREDENTIAL_ROTATED", "GameAccount", accountId, null,
                    Map.of("credentialVersion", next, "securityState", from.name()), null);
        }
        outbox.record("GameAccount", accountId, "CredentialRotated",
                Map.of("gameAccountId", accountId, "ownerId", ownerId, "newVersion", next));
        return GameAccountView.of(a, next);
    }

    /**
     * A strong random password for the owner's next rotation, never one this account has used before. Nothing is
     * stored: the owner sets it on the publisher's side and then submits it through {@link #rotate}.
     */
    @Transactional(readOnly = true)
    public String suggestPassword(UUID ownerId, UUID accountId) {
        GameAccount a = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Game account"));
        if (!a.getOwnerId().equals(ownerId)) {
            throw ApiException.notFound("Game account");
        }
        Set<String> used = usedPasswords(accountId);
        for (int i = 0; i < 10; i++) {
            String p = PasswordGenerator.generate();
            if (!used.contains(p)) {
                return p;
            }
        }
        throw new IllegalStateException("Could not generate an unused password");
    }

    /**
     * Owner fills in the in-game identifier: for accounts created before the game had a key rule, or to fix a typo
     * while the account still waits for verification. Once verified, only support can change it.
     */
    @Transactional
    public GameAccountView setAccountKey(UUID ownerId, UUID accountId, String accountKey) {
        GameAccount a = lockOwned(ownerId, accountId);
        AccountKeyRule rule = catalog.get(a.getGameId()).accountKey();
        if (rule.usesLogin()) {
            throw ApiException.unprocessable("ACCOUNT_KEY_NOT_USED",
                    "Game này nhận diện account bằng tên đăng nhập, không cần nhập định danh.");
        }
        if (a.getAccountKey() != null && a.getSecurityState() != PENDING_VERIFICATION) {
            throw ApiException.conflict("ACCOUNT_KEY_LOCKED",
                    rule.displayLabel() + " đã được xác minh, không thể tự đổi. Hãy liên hệ hỗ trợ nếu cần sửa.");
        }
        Key key = resolveKey(rule, a.getServerRegion(), accountKey);
        if (key.value().equals(a.getAccountKey()) && key.scope().equals(a.getAccountKeyScope())) {
            return GameAccountView.of(a, activeVersion(accountId));
        }
        requireKeyFree(ownerId, a.getGameId(), key, accountId);
        String before = a.getAccountKeyDisplay();
        a.setAccountKey(key.value(), key.display(), key.scope(), clock.instant());
        saveMappingDuplicates(a);
        Map<String, Object> beforeMap = new HashMap<>();
        beforeMap.put("accountKey", before);
        audit.record(ownerId, "ACCOUNT_KEY_SET", "GameAccount", accountId, beforeMap,
                Map.of("accountKey", key.display()), null);
        return GameAccountView.of(a, activeVersion(accountId));
    }

    /** AWAITING_RECLAIM → ROTATION_PENDING (owner takes the account back, STAY_ONLINE mode). */
    @Transactional
    public GameAccountView reclaim(UUID ownerId, UUID accountId) {
        GameAccount a = lockOwned(ownerId, accountId);
        if (a.getSecurityState() == ROTATION_PENDING || a.getSecurityState() == ROTATION_OVERDUE) {
            return GameAccountView.of(a, activeVersion(accountId));
        }
        if (a.getSecurityState() != AWAITING_RECLAIM) {
            throw ApiException.invalidState("Account không ở trạng thái chờ thu hồi.");
        }
        startRotation(a, "OWNER_RECLAIM");
        return GameAccountView.of(a, activeVersion(accountId));
    }

    // ---------------------------------------------------------------- staff actions

    /** UC-44: admin confirms the owner controls recovery email/phone/2FA. */
    @Transactional
    public GameAccountView verifyRecovery(UUID adminId, UUID accountId) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow(() -> ApiException.notFound("Game account"));
        if (a.getRecoveryVerifiedAt() != null && a.getSecurityState() != PENDING_VERIFICATION) {
            return GameAccountView.of(a, activeVersion(accountId));
        }
        if (a.getSecurityState() != PENDING_VERIFICATION) {
            throw ApiException.invalidState("Account không ở trạng thái chờ xác minh.");
        }
        a.markRecoveryVerified(adminId, clock.instant());
        audit.record(adminId, "RECOVERY_VERIFIED", "GameAccount", accountId,
                Map.of("securityState", PENDING_VERIFICATION.name()), Map.of("securityState", AVAILABLE.name()), null);
        outbox.record("GameAccount", accountId, "GameAccountStateChanged", Map.of("gameAccountId", accountId,
                "ownerId", a.getOwnerId(), "from", PENDING_VERIFICATION, "to", AVAILABLE, "reason", "RECOVERY_VERIFIED"));
        return GameAccountView.of(a, activeVersion(accountId));
    }

    /** Admin/Support flags an AVAILABLE account as suspicious. */
    @Transactional
    public GameAccountView flagSuspicious(UUID actorId, UUID accountId, String reasonCode) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow(() -> ApiException.notFound("Game account"));
        if (a.getSecurityState() == SUSPENDED_SECURITY_REVIEW) {
            return GameAccountView.of(a, activeVersion(accountId));
        }
        if (a.getSecurityState() == PENDING_VERIFICATION) {
            throw ApiException.invalidState("Account chưa được xác minh.");
        }
        SecurityState from = a.getSecurityState();
        changeState(a, SUSPENDED_SECURITY_REVIEW, reasonCode);
        audit.record(actorId, "ACCOUNT_FLAGGED_SUSPICIOUS", "GameAccount", accountId,
                Map.of("securityState", from.name()), Map.of("securityState", SUSPENDED_SECURITY_REVIEW.name()), reasonCode);
        return GameAccountView.of(a, activeVersion(accountId));
    }

    /**
     * Executed by an approved ACCOUNT_SECURITY_RESTORE (§8.10): needs a credential version created after the
     * account was suspended.
     */
    @Transactional
    public GameAccountView restoreSecurity(UUID accountId, UUID approvedBy) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow(() -> ApiException.notFound("Game account"));
        if (a.getSecurityState() != SUSPENDED_SECURITY_REVIEW) {
            throw ApiException.invalidState("Account không ở trạng thái SUSPENDED_SECURITY_REVIEW.");
        }
        CredentialVersion active = credentials.findActive(accountId).orElseThrow();
        if (a.getSuspendedAt() == null || !active.getCreatedAt().isAfter(a.getSuspendedAt())) {
            throw ApiException.unprocessable("NEW_CREDENTIAL_REQUIRED",
                    "Chủ account phải cập nhật mật khẩu mới sau khi bị tạm khóa trước khi khôi phục.");
        }
        changeState(a, AVAILABLE, "ACCOUNT_SECURITY_RESTORE");
        audit.record(approvedBy, "ACCOUNT_SECURITY_RESTORED", "GameAccount", accountId,
                Map.of("securityState", SUSPENDED_SECURITY_REVIEW.name()), Map.of("securityState", AVAILABLE.name()), null);
        return GameAccountView.of(a, active.getVersion());
    }

    // ---------------------------------------------------------------- calls from the order/dispute modules

    @Transactional(readOnly = true)
    public boolean acceptsOrders(UUID accountId) {
        return get(accountId).securityState().acceptsOrders();
    }

    /** Locks the account (PESSIMISTIC_WRITE) and, if AVAILABLE, moves it to IN_USE. */
    @Transactional
    public boolean tryMarkInUse(UUID accountId) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow();
        if (a.getSecurityState() != AVAILABLE) {
            return false;
        }
        changeState(a, IN_USE, "ORDER_STARTED");
        return true;
    }

    /** Order ended: credentials are considered leaked → rotation (or wait for reclaim). */
    @Transactional
    public void onOrderEnded(UUID accountId, boolean awaitingReclaim) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow();
        if (a.getSecurityState() != IN_USE) {
            return; // e.g. already suspended by a takeover report
        }
        if (awaitingReclaim) {
            changeState(a, AWAITING_RECLAIM, "ORDER_ENDED");
        } else {
            startRotation(a, "ORDER_ENDED");
        }
    }

    /** UC-43: ACCOUNT_TAKEOVER reported → SUSPENDED_SECURITY_REVIEW. */
    @Transactional
    public void suspendForTakeover(UUID accountId) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow();
        if (a.getSecurityState() != SUSPENDED_SECURITY_REVIEW) {
            changeState(a, SUSPENDED_SECURITY_REVIEW, "ACCOUNT_TAKEOVER");
        }
    }

    /** Decrypts the ACTIVE credential version. Callers are responsible for authorization and audit. */
    @Transactional(readOnly = true)
    public DecryptedCredentials decryptActive(UUID accountId) {
        return decrypt(credentials.findActive(accountId).orElseThrow(() -> ApiException.notFound("Credentials")));
    }

    @Transactional(readOnly = true)
    public SecurityState state(UUID accountId) {
        return accounts.findById(accountId).orElseThrow().getSecurityState();
    }

    // ---------------------------------------------------------------- job

    /** markRotationOverdue job (§9). */
    public int markRotationOverdue(int batchSize) {
        int n = 0;
        for (UUID id : accounts.findRotationOverdueCandidates(clock.instant(), batchSize)) {
            Boolean done = tx.execute(s -> accounts.lockForJob(id).map(a -> {
                if (a.getSecurityState() != ROTATION_PENDING || a.getRotationDueAt() == null
                        || a.getRotationDueAt().isAfter(clock.instant())) {
                    return false;
                }
                Instant due = a.getRotationDueAt();
                changeState(a, ROTATION_OVERDUE, "ROTATION_SLA_EXCEEDED");
                signals.record(a.getOwnerId(), "ROTATION_OVERDUE", null, Map.of("gameAccountId", id.toString()));
                outbox.record("GameAccount", id, "RotationOverdue",
                        Map.of("gameAccountId", id, "ownerId", a.getOwnerId(), "dueAt", due));
                return true;
            }).orElse(false));
            if (Boolean.TRUE.equals(done)) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- helpers

    private void startRotation(GameAccount a, String reason) {
        SecurityState from = a.getSecurityState();
        Instant now = clock.instant();
        a.startRotationWindow(now, now.plus(Duration.ofMinutes(props.rotation().slaMinutes())));
        outbox.record("GameAccount", a.getId(), "GameAccountStateChanged", Map.of("gameAccountId", a.getId(),
                "ownerId", a.getOwnerId(), "from", from, "to", ROTATION_PENDING, "reason", reason,
                "rotationDueAt", a.getRotationDueAt()));
    }

    private void changeState(GameAccount a, SecurityState to, String reason) {
        SecurityState from = a.getSecurityState();
        a.transition(to, clock.instant());
        Map<String, Object> payload = new HashMap<>();
        payload.put("gameAccountId", a.getId());
        payload.put("ownerId", a.getOwnerId());
        payload.put("from", from);
        payload.put("to", to);
        payload.put("reason", reason == null ? "" : reason);
        outbox.record("GameAccount", a.getId(), "GameAccountStateChanged", payload);
    }

    /**
     * Fills login_fingerprint for accounts created before it existed (oldest first). When old data already holds the
     * same login twice, the oldest account keeps the fingerprint and the others are logged for an admin to resolve.
     * Returns how many rows were filled.
     */
    public int backfillLoginFingerprints() {
        List<UUID> ids = tx.execute(s -> accounts.findByLoginFingerprintIsNullOrderByCreatedAt().stream()
                .map(GameAccount::getId).toList());
        int filled = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            try {
                Boolean ok = tx.execute(s -> {
                    GameAccount a = accounts.findForUpdate(id).orElse(null);
                    if (a == null || a.getLoginFingerprint() != null) {
                        return false;
                    }
                    CredentialVersion cv = credentials.findActive(id).orElse(null);
                    if (cv == null) {
                        return false;
                    }
                    String fp = cipher.loginFingerprint(a.getGameId(), decrypt(cv).username());
                    var existing = accounts.findByGameIdAndLoginFingerprint(a.getGameId(), fp);
                    if (existing.isPresent()) {
                        log.warn("Game account {} has the same login as account {}; left without fingerprint", id,
                                existing.get().getId());
                        return false;
                    }
                    a.setLoginFingerprint(fp);
                    accounts.flush();
                    return true;
                });
                if (Boolean.TRUE.equals(ok)) {
                    filled++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not compute login fingerprint for game account {}", id, e);
            }
        }
        return filled;
    }

    /** Normalized in-game identifier plus the scope (server) it is unique in. */
    private record Key(String value, String display, String scope) {}

    private static Key resolveKey(AccountKeyRule rule, String serverRegion, String raw) {
        if (raw == null || raw.isBlank()) {
            throw ApiException.unprocessable("ACCOUNT_KEY_REQUIRED",
                    "Cần nhập " + rule.displayLabel() + " của account để tránh trùng lặp trên VieGG.");
        }
        String value = rule.normalize(raw);
        if (value == null) {
            throw ApiException.unprocessable("ACCOUNT_KEY_INVALID", rule.invalidMessage());
        }
        if (rule.perServer() && (serverRegion == null || serverRegion.isBlank())) {
            throw ApiException.unprocessable("SERVER_REGION_REQUIRED",
                    "Game này phân biệt " + rule.displayLabel() + " theo server, cần nhập server của account.");
        }
        String display = raw.trim();
        return new Key(value, display.length() > 200 ? display.substring(0, 200) : display,
                rule.scope(serverRegion));
    }

    /** The same in-game identifier may be registered only once on the platform, by whichever owner added it first. */
    private void requireKeyFree(UUID ownerId, UUID gameId, Key key, UUID exceptAccountId) {
        accounts.findByGameIdAndAccountKeyScopeAndAccountKey(gameId, key.scope(), key.value())
                .filter(other -> !other.getId().equals(exceptAccountId))
                .ifPresent(other -> {
                    throw duplicate(ownerId, other, "ACCOUNT_KEY");
                });
    }

    /** The same game login may be registered only once on the platform, by whichever owner added it first. */
    private void requireLoginFree(UUID ownerId, UUID gameId, String fingerprint, UUID exceptAccountId) {
        accounts.findByGameIdAndLoginFingerprint(gameId, fingerprint)
                .filter(other -> !other.getId().equals(exceptAccountId))
                .ifPresent(other -> {
                    throw duplicate(ownerId, other, "LOGIN");
                });
    }

    /**
     * Another owner trying to add an account that is already on VieGG is a strong sign of a second VieGG user
     * (e.g. a banned owner coming back): recorded as an abuse signal that survives the rollback.
     */
    private ApiException duplicate(UUID ownerId, GameAccount other, String matchedBy) {
        if (other.getOwnerId().equals(ownerId)) {
            return ApiException.conflict("GAME_ACCOUNT_DUPLICATE", "Bạn đã thêm account game này rồi.");
        }
        try {
            signals.recordIndependently(ownerId, "DUPLICATE_GAME_ACCOUNT", null, Map.of(
                    "gameId", other.getGameId().toString(), "existingAccountId", other.getId().toString(),
                    "existingOwnerId", other.getOwnerId().toString(), "matchedBy", matchedBy));
        } catch (RuntimeException e) {
            log.warn("Could not record duplicate game account signal for user {}", ownerId, e);
        }
        return ApiException.conflict("GAME_ACCOUNT_DUPLICATE",
                "Account game này đã được đăng ký trên VieGG. Nếu đây là account của bạn, hãy liên hệ hỗ trợ.");
    }

    /** Insert, turning a lost race on ux_game_accounts_login into the same API error as the check above. */
    private void saveMappingDuplicates(GameAccount a) {
        try {
            accounts.saveAndFlush(a);
        } catch (DataIntegrityViolationException e) {
            ApiException mapped = DbErrors.map(e);
            throw mapped != null ? mapped : e;
        }
    }

    private GameAccount lockOwned(UUID ownerId, UUID accountId) {
        GameAccount a = accounts.findForUpdate(accountId).orElseThrow(() -> ApiException.notFound("Game account"));
        if (!a.getOwnerId().equals(ownerId)) {
            throw ApiException.notFound("Game account");
        }
        return a;
    }

    /** Every password this account has had (all credential versions, active and retired). */
    private Set<String> usedPasswords(UUID accountId) {
        Set<String> used = new HashSet<>();
        for (CredentialVersion cv : credentials.findByGameAccountIdOrderByVersion(accountId)) {
            used.add(decrypt(cv).password());
        }
        return used;
    }

    private Integer activeVersion(UUID accountId) {
        return credentials.findActive(accountId).map(CredentialVersion::getVersion).orElse(null);
    }

    private CredentialVersion encrypt(UUID accountId, int version, String username, String password, Instant now) {
        try {
            byte[] plain = mapper.writeValueAsBytes(Map.of("username", username, "password", password));
            CredentialCipher.Encrypted e = cipher.encrypt(accountId, version, plain);
            return new CredentialVersion(UUID.randomUUID(), accountId, version, e.ciphertext(), e.iv(), e.keyId(), now);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize credentials");
        }
    }

    private DecryptedCredentials decrypt(CredentialVersion cv) {
        byte[] plain = cipher.decrypt(cv.getGameAccountId(), cv.getVersion(), cv.getCiphertext(), cv.getIv(), cv.getKeyId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> m = mapper.readValue(plain, Map.class);
            return new DecryptedCredentials(m.get("username"), m.get("password"), cv.getVersion());
        } catch (IOException e) {
            throw new IllegalStateException("Corrupted credential payload");
        }
    }

    static String maskUsername(String username) {
        String u = username == null ? "" : username.trim();
        if (u.length() <= 2) {
            return u.isEmpty() ? "***" : u.charAt(0) + "***";
        }
        if (u.length() <= 4) {
            return u.substring(0, 1) + "***" + u.substring(u.length() - 1);
        }
        return u.substring(0, 2) + "***" + u.substring(u.length() - 2);
    }
}
