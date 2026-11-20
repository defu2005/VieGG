package com.rentgg.identity.api;

import com.rentgg.identity.application.AuthorityService;
import com.rentgg.identity.application.TotpService;
import com.rentgg.identity.infrastructure.UserRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

/**
 * Enrolment of the staff second factor. Callers are authenticated and must be staff in the database: an unverified
 * staff token has no staff permissions, so enrolment is not gated by {@code @PreAuthorize}.
 */
@RestController
@RequestMapping("/api/v1/me/mfa/totp")
public class TotpController {

    private final TotpService totp;
    private final AuthorityService authz;
    private final UserRepository users;
    private final Clock clock;

    public TotpController(TotpService totp, AuthorityService authz, UserRepository users, Clock clock) {
        this.totp = totp;
        this.authz = authz;
        this.users = users;
        this.clock = clock;
    }

    /** The secret is returned only here, once. */
    public record StartResponse(String secret, String otpauthUri) {}

    public record ConfirmRequest(@NotBlank String code) {}

    @PostMapping("/start")
    public StartResponse start() {
        UUID id = requireStaff();
        String email = users.findById(id).orElseThrow().getEmail();
        TotpService.Enrollment e = totp.startEnrollment(id, email);
        return new StartResponse(e.secret(), e.otpauthUri());
    }

    @PostMapping("/confirm")
    public void confirm(@Valid @RequestBody ConfirmRequest req) {
        UUID id = requireStaff();
        totp.confirmEnrollment(id, req.code().trim(), clock.instant());
    }

    private UUID requireStaff() {
        UUID id = CurrentUser.id();
        if (!authz.fresh(id).staff()) {
            throw ApiException.forbidden("NOT_STAFF", "Chỉ tài khoản nhân viên mới bật xác thực hai bước.");
        }
        return id;
    }
}
