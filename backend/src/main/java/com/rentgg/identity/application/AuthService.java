package com.rentgg.identity.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.config.RentGgProperties;
import com.rentgg.identity.domain.RefreshToken;
import com.rentgg.identity.domain.User;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.identity.infrastructure.RefreshTokenRepository;
import com.rentgg.identity.infrastructure.UserRepository;
import com.rentgg.shared.crypto.Hmac;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final UserService userService;
    private final AuditService audit;
    private final RentGgProperties props;
    private final Clock clock;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwt, UserService userService, AuditService audit, RentGgProperties props, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.userService = userService;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public UserView register(String email, String password, String displayName, LocalDate dateOfBirth) {
        if (password == null || password.length() < 10) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Mật khẩu tối thiểu 10 ký tự.");
        }
        String normalized = email.trim().toLowerCase();
        User u = new User(normalized, passwordEncoder.encode(password), displayName.trim(), dateOfBirth, clock.instant());
        if (u.ageOn(userService.today()) < UserService.MIN_AGE) {
            throw ApiException.forbidden("AGE_RESTRICTED", "Bạn cần đủ 18 tuổi để sử dụng VieGG.");
        }
        if (users.findByEmail(normalized).isPresent()) {
            throw ApiException.conflict("EMAIL_TAKEN", "Email đã được sử dụng.");
        }
        users.saveAndFlush(u);
        return UserView.of(u);
    }

    @Transactional
    public Tokens login(String email, String password) {
        User u = users.findByEmail(email.trim().toLowerCase()).orElse(null);
        if (u == null || !passwordEncoder.matches(password, u.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email hoặc mật khẩu không đúng.");
        }
        if (u.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("USER_SUSPENDED", "Tài khoản của bạn đang bị đình chỉ.");
        }
        if (u.getRoles().stream().anyMatch(r -> !r.equals("USER"))) {
            audit.record(u.getId(), "STAFF_LOGIN", "User", u.getId(), null, Map.of("roles", u.getRoles().toString()), null);
        }
        return issue(u);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Tokens refresh(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token không hợp lệ.");
        }
        Instant now = clock.instant();
        RefreshToken token = refreshTokens.findByHashForUpdate(Hmac.sha256(rawRefreshToken))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token không hợp lệ."));
        if (token.isRevoked()) {
            // Reuse of a rotated token: assume theft and revoke the whole family (§12.1).
            refreshTokens.revokeAllForUser(token.getUserId(), now);
            audit.record(token.getUserId(), "REFRESH_TOKEN_REUSE_DETECTED", "User", token.getUserId(), null, null, null);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Refresh token đã bị thu hồi.");
        }
        if (!token.getExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "Refresh token đã hết hạn.");
        }
        User u = users.findById(token.getUserId()).orElseThrow();
        if (u.getStatus() == UserStatus.SUSPENDED) {
            throw ApiException.forbidden("USER_SUSPENDED", "Tài khoản của bạn đang bị đình chỉ.");
        }
        Tokens next = issue(u);
        token.revoke(now, next.refreshTokenId());
        return next;
    }

    /** Revokes one refresh token (this device). Unknown or already revoked tokens are ignored. */
    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByHashForUpdate(Hmac.sha256(rawRefreshToken))
                .ifPresent(t -> t.revoke(clock.instant(), null));
    }

    /** Revokes every refresh token of the user: other devices are logged out once their access token expires. */
    @Transactional
    public void logoutEverywhere(UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId, clock.instant());
        audit.record(userId, "LOGOUT_ALL_DEVICES", "User", userId, null, Map.of("revokedSessions", revoked), null);
    }

    /**
     * Checks the current password, stores the new one and revokes every session.
     * Returns fresh tokens so the device that made the change stays logged in.
     */
    @Transactional
    public Tokens changePassword(UUID userId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < 10) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Mật khẩu mới tối thiểu 10 ký tự.");
        }
        User u = users.findForUpdate(userId).orElseThrow(() -> ApiException.notFound("Người dùng"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, u.getPasswordHash())) {
            throw ApiException.badRequest("INVALID_CURRENT_PASSWORD", "Mật khẩu hiện tại không đúng.");
        }
        if (passwordEncoder.matches(newPassword, u.getPasswordHash())) {
            throw ApiException.badRequest("PASSWORD_UNCHANGED", "Mật khẩu mới phải khác mật khẩu hiện tại.");
        }
        Instant now = clock.instant();
        u.setPasswordHash(passwordEncoder.encode(newPassword), now);
        refreshTokens.revokeAllForUser(userId, now);
        audit.record(userId, "PASSWORD_CHANGED", "User", userId, null, null, null);
        return issue(u);
    }

    /**
     * Dev time travel only (called from the dev-profile controller): fresh tokens for an already authenticated
     * user, so moving the clock past the token lifetimes does not log the tester out.
     */
    @Transactional
    public Tokens reissueAfterDevTimeTravel(UUID userId) {
        return issue(users.findById(userId).orElseThrow());
    }

    private Tokens issue(User u) {
        JwtService.IssuedToken access = jwt.issueAccessToken(u.getId(), u.getRoles(), u.getStatus().name());
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        RefreshToken rt = new RefreshToken(u.getId(), Hmac.sha256(raw), now.plus(props.security().refreshTokenTtl()), now);
        refreshTokens.save(rt);
        return new Tokens(access.value(), access.expiresAt(), raw, rt.getExpiresAt(), rt.getId());
    }

    public record Tokens(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                         Instant refreshTokenExpiresAt, UUID refreshTokenId) {}
}
