package com.rentgg.identity.api;

import com.rentgg.config.RentGgProperties;
import com.rentgg.identity.application.AuthService;
import com.rentgg.identity.application.AuthorityService;
import com.rentgg.identity.application.UserView;
import com.rentgg.shared.ratelimit.RateLimiter;
import com.rentgg.shared.ratelimit.RateLimiter.Limit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final RateLimiter rateLimiter;
    private final RentGgProperties props;
    private final AuthorityService authorities;

    public AuthController(AuthService auth, RateLimiter rateLimiter, RentGgProperties props,
                          AuthorityService authorities) {
        this.authorities = authorities;
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public MeController.MeDto register(@Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
        rateLimiter.consume("register:" + http.getRemoteAddr(), Limit.perHour(props.rateLimit().registerPerHour()));
        UserView u = auth.register(req.email(), req.password(), req.displayName(), req.dateOfBirth());
        return MeController.MeDto.of(u, authorities.fresh(u.id()));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        rateLimiter.consume("login:" + http.getRemoteAddr() + ":" + req.email().trim().toLowerCase(),
                Limit.perMinute(props.rateLimit().loginPerMinute()), Limit.perHour(props.rateLimit().loginPerHour()));
        return TokenResponse.of(auth.login(req.email(), req.password()));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return TokenResponse.of(auth.refresh(req.refreshToken()));
    }

    /** Logs this device out: revokes its refresh token. Public so it works after the access token expired. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
    }

    public record RegisterRequest(@NotBlank @Email String email,
                                  @NotBlank @Size(min = 10, max = 200) String password,
                                  @NotBlank @Size(max = 100) String displayName,
                                  @NotNull LocalDate dateOfBirth) {
        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + ", password=***]";
        }
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
        @Override
        public String toString() {
            return "LoginRequest[email=" + email + ", password=***]";
        }
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
        @Override
        public String toString() {
            return "RefreshRequest[refreshToken=***]";
        }
    }

    public record TokenResponse(String accessToken, Instant accessTokenExpiresAt, String refreshToken,
                                Instant refreshTokenExpiresAt, String tokenType) {
        static TokenResponse of(AuthService.Tokens t) {
            return new TokenResponse(t.accessToken(), t.accessTokenExpiresAt(), t.refreshToken(),
                    t.refreshTokenExpiresAt(), "Bearer");
        }

        @Override
        public String toString() {
            return "TokenResponse[***]";
        }
    }
}
