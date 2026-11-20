package com.rentgg.identity.api;

import com.rentgg.config.RentGgProperties;
import com.rentgg.identity.application.AuthService;
import com.rentgg.identity.application.TotpService;
import com.rentgg.identity.application.AuthorityService;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.application.UserView;
import com.rentgg.shared.security.AuthorityResolver;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.ratelimit.RateLimiter;
import com.rentgg.shared.ratelimit.RateLimiter.Limit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserService users;
    private final AuthorityService authorities;
    private final AuthService auth;
    private final RateLimiter rateLimiter;
    private final RentGgProperties props;

    private final TotpService totp;

    public MeController(UserService users, AuthorityService authorities, AuthService auth, RateLimiter rateLimiter,
                        RentGgProperties props, TotpService totp) {
        this.users = users;
        this.authorities = authorities;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.totp = totp;
    }

    /**
     * For the caller, permissions are the ones the access token grants (empty for unverified staff), so the page
     * matches what the API will allow. The database value is used only for another user.
     */
    private MeDto dto(UserView u) {
        AuthorityResolver.Authorities db = authorities.resolve(u.id());
        boolean self = CurrentUser.optionalId().filter(u.id()::equals).isPresent();
        Set<String> permissions = self ? CurrentUser.permissions() : db.permissions();
        MeDto.Mfa mfa = new MeDto.Mfa(db.staff() && props.security().staffMfaRequired(), totp.isEnrolled(u.id()),
                self && CurrentUser.mfaVerified());
        return MeDto.of(u, permissions, db.staff(), mfa);
    }

    @GetMapping
    public MeDto me() {
        return dto(users.get(CurrentUser.id()));
    }

    @PutMapping
    public MeDto updateProfile(@Valid @RequestBody UpdateProfileRequest req) {
        return dto(users.updateProfile(CurrentUser.id(), req.displayName()));
    }

    /** Revokes every session; returns new tokens so this device stays logged in. */
    @PostMapping("/password")
    public AuthController.TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        // Same budget as login: stops guessing the current password with a stolen access token.
        rateLimiter.consume("password:" + CurrentUser.id(),
                Limit.perMinute(props.rateLimit().loginPerMinute()), Limit.perHour(props.rateLimit().loginPerHour()));
        return AuthController.TokenResponse.of(auth.changePassword(CurrentUser.id(), req.currentPassword(), req.newPassword(), CurrentUser.mfaVerified()));
    }

    /** Logs out every device (including this one once the client drops its tokens). */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll() {
        auth.logoutEverywhere(CurrentUser.id());
    }

    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String displayName) {}

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 10, max = 200) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    /** MVP KYC: declarative data reviewed manually by admin; no ID images stored (§5.3). */
    @PostMapping("/kyc")
    public MeDto submitKyc(@Valid @RequestBody KycRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fullName", req.fullName());
        payload.put("idNumberLast4", req.idNumber().substring(Math.max(0, req.idNumber().length() - 4)));
        payload.put("phone", req.phone());
        return dto(users.submitKyc(CurrentUser.id(), payload));
    }

    public record KycRequest(@NotBlank @Size(max = 120) String fullName,
                             @NotBlank @Size(min = 6, max = 20) String idNumber,
                             @Size(max = 20) String phone) {}

    /**
     * {@code permissions}: what the user may do right now; clients should gate UI on these, not on role names.
     * {@code staff}: staff account (platform operator); clients show the back office instead of customer features.
     */
    public record MeDto(UUID id, String email, String displayName, LocalDate dateOfBirth, Set<String> roles,
                        Set<String> permissions, boolean staff, String status, String kycStatus, String riskTier,
                        Mfa mfa) {
        /** {@code required}: the staff second factor applies to this account; {@code verified}: this session passed it. */
        public record Mfa(boolean required, boolean enrolled, boolean verified) {}

        static MeDto of(UserView u, Set<String> permissions, boolean staff, Mfa mfa) {
            return new MeDto(u.id(), u.email(), u.displayName(), u.dateOfBirth(), new TreeSet<>(u.roles()),
                    new TreeSet<>(permissions), staff, u.status().name(), u.kycStatus().name(), u.riskTier().name(), mfa);
        }
    }
}
