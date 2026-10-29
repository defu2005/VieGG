package com.rentgg.gameaccount.api;

import com.rentgg.shared.web.PageResponse;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.gameaccount.application.GameAccountView;
import com.rentgg.gameaccount.domain.SecurityState;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class GameAccountController {

    private final GameAccountService service;

    public GameAccountController(GameAccountService service) {
        this.service = service;
    }

    @PostMapping("/game-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('game_account:manage_own')")
    public GameAccountView create(@Valid @RequestBody CreateRequest req) {
        return service.create(CurrentUser.id(), req.gameId(), req.serverRegion(), req.username(), req.password(),
                req.recoveryEmailOwned(), req.recoveryPhoneOwned(), req.twoFactorOwned(), req.accountKey());
    }

    /** Fills in (or, before verification, fixes) the in-game identifier the game's rule asks for. */
    @PutMapping("/game-accounts/{id}/account-key")
    @PreAuthorize("hasAuthority('game_account:manage_own')")
    public GameAccountView setAccountKey(@PathVariable UUID id, @Valid @RequestBody AccountKeyRequest req) {
        return service.setAccountKey(CurrentUser.id(), id, req.accountKey());
    }

    @GetMapping("/game-accounts")
    public PageResponse<GameAccountView> mine(@RequestParam(defaultValue = "false") boolean unlisted,
                                              @RequestParam(required = false) List<UUID> id,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return PageResponse.of(service.listByOwner(CurrentUser.id(), unlisted, id, p, s), x -> x);
    }

    @GetMapping("/game-accounts/{id}")
    public GameAccountView get(@PathVariable UUID id) {
        return service.getOwned(CurrentUser.id(), id);
    }

    @PostMapping("/game-accounts/{id}/rotation")
    @PreAuthorize("hasAuthority('game_account:manage_own')")
    public GameAccountView rotate(@PathVariable UUID id, @Valid @RequestBody RotationRequest req) {
        return service.rotate(CurrentUser.id(), id, req.newUsername(), req.newPassword(), req.loggedOutAllDevices());
    }

    /** A fresh random password this account has never used; POST so nothing caches it. */
    @PostMapping("/game-accounts/{id}/password-suggestion")
    @PreAuthorize("hasAuthority('game_account:manage_own')")
    public ResponseEntity<PasswordSuggestion> suggestPassword(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new PasswordSuggestion(service.suggestPassword(CurrentUser.id(), id)));
    }

    @PostMapping("/game-accounts/{id}/reclaim")
    @PreAuthorize("hasAuthority('game_account:manage_own')")
    public GameAccountView reclaim(@PathVariable UUID id) {
        return service.reclaim(CurrentUser.id(), id);
    }

    @GetMapping("/admin/game-accounts")
    @PreAuthorize("hasAuthority('game_account:verify_recovery')")
    public PageResponse<GameAccountView> byState(@RequestParam(defaultValue = "PENDING_VERIFICATION") SecurityState state,
                                                 @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return PageResponse.of(service.listByState(state, p, s), x -> x);
    }

    @PostMapping("/admin/game-accounts/{id}/verify-recovery")
    @PreAuthorize("hasAuthority('game_account:verify_recovery')")
    public GameAccountView verifyRecovery(@PathVariable UUID id) {
        return service.verifyRecovery(CurrentUser.id(), id);
    }

    @PostMapping("/admin/game-accounts/{id}/flag-suspicious")
    @PreAuthorize("hasAuthority('game_account:flag_suspicious')")
    public GameAccountView flag(@PathVariable UUID id, @Valid @RequestBody FlagRequest req) {
        return service.flagSuspicious(CurrentUser.id(), id, req.reasonCode());
    }

    public record CreateRequest(@NotNull UUID gameId, @Size(max = 60) String serverRegion,
                                @NotBlank @Size(max = 200) String username,
                                @NotBlank @Size(max = 200) String password,
                                boolean recoveryEmailOwned, boolean recoveryPhoneOwned, boolean twoFactorOwned,
                                /** In-game identifier (UID, UUID, name...); required unless the game uses the login. */
                                @Size(max = 200) String accountKey) {
        @Override
        public String toString() {
            return "CreateRequest[gameId=" + gameId + ", username=***, password=***]";
        }
    }

    public record RotationRequest(@Size(max = 200) String newUsername, @NotBlank @Size(max = 200) String newPassword,
                                  boolean loggedOutAllDevices) {
        @Override
        public String toString() {
            return "RotationRequest[newUsername=***, newPassword=***, loggedOutAllDevices=" + loggedOutAllDevices + "]";
        }
    }

    public record PasswordSuggestion(String password) {
        @Override
        public String toString() {
            return "PasswordSuggestion[password=***]";
        }
    }

    public record FlagRequest(@NotBlank String reasonCode) {}

    public record AccountKeyRequest(@NotBlank @Size(max = 200) String accountKey) {}
}
