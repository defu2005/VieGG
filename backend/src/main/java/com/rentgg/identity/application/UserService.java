package com.rentgg.identity.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.identity.domain.KycStatus;
import com.rentgg.identity.domain.KycSubmission;
import com.rentgg.identity.domain.RiskTier;
import com.rentgg.identity.domain.User;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.identity.infrastructure.KycSubmissionRepository;
import com.rentgg.identity.infrastructure.RefreshTokenRepository;
import com.rentgg.identity.infrastructure.UserRepository;
import com.rentgg.notification.application.StaffDirectory;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.outbox.OutboxService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public facade of the identity module. */
@Service
public class UserService implements StaffDirectory {

    public static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MIN_AGE = 18;

    private final UserRepository users;
    private final KycSubmissionRepository kyc;
    private final RefreshTokenRepository refreshTokens;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public UserService(UserRepository users, KycSubmissionRepository kyc, RefreshTokenRepository refreshTokens,
                       AuditService audit, OutboxService outbox, Clock clock) {
        this.users = users;
        this.kyc = kyc;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserView get(UUID userId) {
        return UserView.of(users.findById(userId).orElseThrow(() -> ApiException.notFound("Người dùng")));
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(VN));
    }

    /**
     * Common checks before any money-moving action (order, listing, withdrawal): reads status from DB,
     * not from the JWT claim (§12.1).
     */
    @Transactional(readOnly = true)
    public UserView requireEligible(UUID userId, int minAge, boolean blockRestricted) {
        UserView u = get(userId);
        if (u.status() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("USER_SUSPENDED", "Tài khoản của bạn đang bị đình chỉ.");
        }
        if (blockRestricted && u.status() == UserStatus.RESTRICTED) {
            throw ApiException.forbidden("USER_RESTRICTED", "Tài khoản của bạn đang bị hạn chế giao dịch.");
        }
        int required = Math.max(MIN_AGE, minAge);
        if (users.findById(userId).orElseThrow().ageOn(today()) < required) {
            throw ApiException.forbidden("AGE_RESTRICTED", "Bạn chưa đủ " + required + " tuổi để giao dịch.");
        }
        return u;
    }

    @Transactional
    public UserView updateProfile(UUID userId, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Tên hiển thị từ 1 đến 100 ký tự.");
        }
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        String before = u.getDisplayName();
        if (!before.equals(name)) {
            u.setDisplayName(name, clock.instant());
            audit.record(userId, "PROFILE_UPDATED", "User", userId,
                    Map.of("displayName", before), Map.of("displayName", name), null);
        }
        return UserView.of(u);
    }

    @Transactional
    public UserView submitKyc(UUID userId, Map<String, Object> payload) {
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        if (u.getKycStatus() == KycStatus.VERIFIED) {
            return UserView.of(u);
        }
        if (u.getKycStatus() != KycStatus.PENDING) {
            kyc.save(new KycSubmission(userId, payload, clock.instant()));
            u.setKycStatus(KycStatus.PENDING, clock.instant());
            audit.record(userId, "KYC_SUBMITTED", "User", userId, null, Map.of("kycStatus", "PENDING"), null);
        }
        return UserView.of(u);
    }

    @Transactional
    public UserView decideKyc(UUID actorId, UUID userId, boolean verify, String note) {
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        KycStatus before = u.getKycStatus();
        KycStatus target = verify ? KycStatus.VERIFIED : KycStatus.REJECTED;
        if (before == target) {
            return UserView.of(u);
        }
        u.setKycStatus(target, clock.instant());
        kyc.findFirstByUserIdOrderByCreatedAtDesc(userId)
                .ifPresent(s -> s.decide(target.name(), actorId, note, clock.instant()));
        audit.record(actorId, verify ? "KYC_VERIFIED" : "KYC_REJECTED", "User", userId,
                Map.of("kycStatus", before.name()), Map.of("kycStatus", target.name()), null);
        return UserView.of(u);
    }

    @Transactional
    public UserView changeStatus(UUID actorId, UUID userId, UserStatus target, String reasonCode) {
        if (reasonCode == null || reasonCode.isBlank()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "reasonCode là bắt buộc.");
        }
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        UserStatus before = u.getStatus();
        if (before == target) {
            return UserView.of(u);
        }
        u.setStatus(target, clock.instant());
        if (target == UserStatus.SUSPENDED) {
            refreshTokens.revokeAllForUser(userId, clock.instant());
        }
        audit.record(actorId, "USER_STATUS_CHANGED", "User", userId,
                Map.of("status", before.name()), Map.of("status", target.name()), reasonCode);
        outbox.record("User", userId, "UserStatusChanged",
                Map.of("userId", userId, "from", before, "to", target, "reason", reasonCode));
        return UserView.of(u);
    }

    @Transactional
    public void setRiskTier(UUID actorId, UUID userId, RiskTier tier, String reasonCode) {
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        if (u.getRiskTier() != tier) {
            RiskTier before = u.getRiskTier();
            u.setRiskTier(tier, clock.instant());
            audit.record(actorId, "USER_RISK_TIER_CHANGED", "User", userId,
                    Map.of("riskTier", before.name()), Map.of("riskTier", tier.name()), reasonCode);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> userIdsWithRole(String role) {
        return users.findIdsByRole(role);
    }
}
