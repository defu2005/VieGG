package com.rentgg.catalog.api;

import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.application.GameView;
import com.rentgg.catalog.domain.GamePolicy;
import com.rentgg.catalog.domain.SuggestedPackage;
import com.rentgg.catalog.domain.RiskSide;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.security.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
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
public class GameController {

    private final CatalogService catalog;

    public GameController(CatalogService catalog) {
        this.catalog = catalog;
    }

    /**
     * Only ALLOWED games by default, for everyone: staff browsing the marketplace must not see games under review.
     * The admin catalog passes {@code all=true} to get every ToS status.
     */
    @GetMapping("/games")
    public List<GameView> list(@RequestParam(defaultValue = "false") boolean all) {
        if (all && !CurrentUser.has(Permission.GAME_READ_HIDDEN)) {
            throw ApiException.forbidden("FORBIDDEN", "Bạn không có quyền xem game chưa được phép cho thuê.");
        }
        return catalog.list(all);
    }

    @GetMapping("/games/{id}")
    public GameView get(@PathVariable UUID id) {
        GameView g = catalog.get(id);
        if (!g.allowed() && !CurrentUser.has(Permission.GAME_READ_HIDDEN)) {
            throw ApiException.notFound("Game");
        }
        return g;
    }

    @PostMapping("/admin/games")
    @PreAuthorize("hasAuthority('game:create')")
    @ResponseStatus(HttpStatus.CREATED)
    public GameView create(@Valid @RequestBody CreateGameRequest req) {
        return catalog.create(CurrentUser.id(), req.code(), req.policy(), toInputs(req.risks()));
    }

    @PutMapping("/admin/games/{id}")
    @PreAuthorize("hasAuthority('game:update')")
    public GameView update(@PathVariable UUID id, @Valid @RequestBody GamePolicy policy) {
        return catalog.update(CurrentUser.id(), id, policy);
    }

    /** Replaces the game's risk list; risks left out are archived (old orders keep their snapshot). */
    @PutMapping("/admin/games/{id}/risks")
    @PreAuthorize("hasAuthority('game:update')")
    public GameView replaceRisks(@PathVariable UUID id, @Valid @RequestBody RisksRequest req) {
        return catalog.replaceRisks(CurrentUser.id(), id, toInputs(req.risks()));
    }

    /** Replaces the rental packages suggested to owners (owners still choose which ones to offer). */
    @PutMapping("/admin/games/{id}/packages")
    @PreAuthorize("hasAuthority('game:update')")
    public GameView replacePackages(@PathVariable UUID id, @Valid @RequestBody PackagesRequest req) {
        return catalog.replaceSuggestedPackages(CurrentUser.id(), id, req.packages());
    }

    public record PackagesRequest(@NotNull @Size(max = SuggestedPackage.MAX_PACKAGES) List<SuggestedPackage> packages) {}

    private static List<CatalogService.RiskInput> toInputs(List<RiskRequest> risks) {
        return risks == null ? null : risks.stream().map(r -> new CatalogService.RiskInput(r.id(), r.side(), r.name(),
                r.description(), r.recommendation(),
                r.complaintWindowMinutes(), r.stackable() == null || r.stackable())).toList();
    }

    /** {@code risks} is optional: the game can also get its risks later through PUT /admin/games/{id}/risks. */
    public record CreateGameRequest(@NotBlank @Pattern(regexp = "[a-z0-9_-]{2,40}") String code,
                                    @NotNull @Valid GamePolicy policy,
                                    @Size(max = CatalogService.MAX_RISKS) List<@Valid RiskRequest> risks) {}

    public record RisksRequest(@NotNull @Size(max = CatalogService.MAX_RISKS) List<@Valid RiskRequest> risks) {}

    public record RiskRequest(UUID id, @NotNull RiskSide side, @NotBlank @Size(max = 120) String name,
                              @Size(max = 1000) String description,
                              /** Free-text advice on the deposit / compensation, e.g. "Nên bằng 30–50% giá trị account". */
                              @Size(max = 500) String recommendation,
                              @Min(1) @Max(43_200) Integer complaintWindowMinutes,
                              /** OWNER risks: null/true = stackable, false = exclusive. */
                              Boolean stackable) {}
}
