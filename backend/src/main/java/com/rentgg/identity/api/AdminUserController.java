package com.rentgg.identity.api;

import com.rentgg.identity.application.AbuseSignalService;
import com.rentgg.identity.application.AuthorityService;
import com.rentgg.identity.application.UserService;
import com.rentgg.identity.application.UserView;
import com.rentgg.identity.domain.RiskTier;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final UserService users;
    private final AbuseSignalService signals;
    private final AuthorityService authorities;

    public AdminUserController(UserService users, AbuseSignalService signals, AuthorityService authorities) {
        this.users = users;
        this.signals = signals;
        this.authorities = authorities;
    }

    private MeController.MeDto dto(UserView u) {
        return MeController.MeDto.of(u, authorities.resolve(u.id()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    public MeController.MeDto get(@PathVariable UUID id) {
        return dto(users.get(id));
    }

    @PostMapping("/{id}/kyc")
    @PreAuthorize("hasAuthority('user:kyc_decide')")
    public MeController.MeDto decideKyc(@PathVariable UUID id, @Valid @RequestBody KycDecision req) {
        return dto(users.decideKyc(CurrentUser.id(), id, "VERIFY".equals(req.decision()), req.note()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:change_status')")
    public MeController.MeDto changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChange req) {
        return dto(users.changeStatus(CurrentUser.id(), id, req.status(), req.reasonCode()));
    }

    @PostMapping("/{id}/risk-tier")
    @PreAuthorize("hasAuthority('user:change_risk_tier')")
    public MeController.MeDto changeRiskTier(@PathVariable UUID id, @Valid @RequestBody RiskTierChange req) {
        users.setRiskTier(CurrentUser.id(), id, req.riskTier(), req.reasonCode());
        return dto(users.get(id));
    }

    @GetMapping("/{id}/abuse-signals")
    @PreAuthorize("hasAuthority('user:read_abuse_signals')")
    public List<AbuseSignalService.Signal> abuseSignals(@PathVariable UUID id) {
        return signals.list(id);
    }

    public record KycDecision(@NotBlank @Pattern(regexp = "VERIFY|REJECT") String decision, String note) {}

    public record StatusChange(@NotNull UserStatus status, @NotBlank String reasonCode) {}

    public record RiskTierChange(@NotNull RiskTier riskTier, @NotBlank String reasonCode) {}
}
