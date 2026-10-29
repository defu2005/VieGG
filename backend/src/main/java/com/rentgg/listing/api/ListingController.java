package com.rentgg.listing.api;

import com.rentgg.catalog.application.CatalogService;
import com.rentgg.catalog.application.GameView;
import com.rentgg.gameaccount.application.GameAccountService;
import com.rentgg.listing.application.AccountBlockService;
import com.rentgg.listing.application.ListingService;
import com.rentgg.listing.application.ListingView;
import com.rentgg.listing.domain.EndMode;
import com.rentgg.listing.domain.ListingStatus;
import com.rentgg.listing.domain.PricingTier;
import com.rentgg.listing.domain.RiskTerm;
import com.rentgg.catalog.application.GameRiskView;
import com.rentgg.catalog.domain.RiskSide;
import jakarta.validation.constraints.PositiveOrZero;
import com.rentgg.shared.error.ApiException;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.security.Permission;
import com.rentgg.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ListingController {

    private final ListingService listings;
    private final CatalogService catalog;
    private final GameAccountService gameAccounts;
    private final AccountBlockService blocks;

    public ListingController(ListingService listings, CatalogService catalog, GameAccountService gameAccounts,
                             AccountBlockService blocks) {
        this.listings = listings;
        this.catalog = catalog;
        this.gameAccounts = gameAccounts;
        this.blocks = blocks;
    }

    /** The owner's busy time on the listing's account that has not ended yet (with their private notes). */
    @GetMapping("/listings/{id}/busy-blocks")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public List<AccountBlockService.BlockView> busyBlocks(@PathVariable UUID id) {
        return blocks.list(CurrentUser.id(), id);
    }

    /** Marks the account busy for [startAt, endAt): nobody can book it then. Returns the updated list. */
    @PostMapping("/listings/{id}/busy-blocks")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public List<AccountBlockService.BlockView> addBusyBlock(@PathVariable UUID id, @Valid @RequestBody BusyBlockRequest req) {
        return blocks.add(CurrentUser.id(), id, req.startAt(), req.endAt(), req.note());
    }

    @DeleteMapping("/listings/{id}/busy-blocks/{blockId}")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public List<AccountBlockService.BlockView> removeBusyBlock(@PathVariable UUID id, @PathVariable UUID blockId) {
        return blocks.remove(CurrentUser.id(), id, blockId);
    }

    @PostMapping("/listings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public ListingView create(@Valid @RequestBody ListingRequest req) {
        if (req.gameAccountId() == null) {
            throw ApiException.badRequest("VALIDATION_ERROR", "gameAccountId là bắt buộc.");
        }
        return listings.create(CurrentUser.id(), req.gameAccountId(), req.content());
    }

    @PutMapping("/listings/{id}")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public ListingView update(@PathVariable UUID id, @Valid @RequestBody ListingRequest req) {
        return listings.update(CurrentUser.id(), id, req.version(), req.content());
    }

    @PostMapping("/listings/{id}/submit")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public ListingView submit(@PathVariable UUID id) {
        return listings.submit(CurrentUser.id(), id);
    }

    @PostMapping("/listings/{id}/pause")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public ListingView pause(@PathVariable UUID id) {
        return listings.pause(CurrentUser.id(), id);
    }

    @GetMapping("/listings/mine")
    public PageResponse<ListingView> mine(@RequestParam(required = false) List<UUID> gameAccountId,
                                          @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return listings.listByOwner(CurrentUser.id(), gameAccountId, p, s);
    }

    @GetMapping("/listings/search")
    public PageResponse<ListingView> search(@RequestParam(required = false) UUID gameId,
                                            @RequestParam(required = false) Long minPrice,
                                            @RequestParam(required = false) Long maxPrice,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return listings.search(gameId, minPrice, maxPrice, from, to, p, s);
    }

    /** Public detail; non-ACTIVE listings are visible only to their owner and staff. */
    @GetMapping("/listings/{id}")
    public ListingDetail get(@PathVariable UUID id) {
        ListingView l = listings.get(id);
        GameView game = requireVisible(l);
        boolean bookable = l.status() == ListingStatus.ACTIVE && game.allowed()
                && gameAccounts.acceptsOrders(l.gameAccountId());
        return new ListingDetail(l, new Policy(game.handoverMode().name(), game.depositHoldHours(),
                game.complaintWindowMinutes(), game.minDurationMinutes(), game.maxDurationMinutes(), game.graceMinutes(),
                l.effectiveRenterFreeCancelMinutes(game.renterFreeCancelMinutes()), game.lateCancelRefundBps(),
                game.minAge()),
                listings.busyPeriods(l.gameAccountId()), bookable, game.withListingRisks(l.ownerRisks()).risks());
    }

    /** Account screenshots: multipart field "file", JPEG/PNG/WebP up to 1 MB, at most 8 per listing. */
    @PostMapping(value = "/listings/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public ListingView addImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
        return listings.addImage(CurrentUser.id(), id, file.getBytes());
    }

    @DeleteMapping("/listings/{id}/images/{imageId}")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public ListingView removeImage(@PathVariable UUID id, @PathVariable UUID imageId) {
        return listings.removeImage(CurrentUser.id(), id, imageId);
    }

    /** Same visibility as the listing detail: public for ACTIVE listings, otherwise owner and staff only. */
    @GetMapping("/listings/{id}/images/{imageId}")
    public ResponseEntity<byte[]> image(@PathVariable UUID id, @PathVariable UUID imageId) {
        requireVisible(listings.get(id));
        ListingService.ImageData img = listings.image(id, imageId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(img.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(img.data());
    }

    private GameView requireVisible(ListingView l) {
        boolean privileged = CurrentUser.optionalId().map(u -> u.equals(l.ownerId())).orElse(false)
                || CurrentUser.has(Permission.LISTING_READ_ANY);
        GameView game = catalog.get(l.gameId());
        if (!privileged && (l.status() != ListingStatus.ACTIVE || !game.allowed())) {
            throw ApiException.notFound("Listing");
        }
        return game;
    }

    @GetMapping("/admin/listings")
    @PreAuthorize("hasAuthority('listing:review_queue')")
    public PageResponse<ListingView> byStatus(@RequestParam(defaultValue = "PENDING_REVIEW") ListingStatus status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? 20 : Math.min(100, Math.max(1, size));
        return listings.listByStatus(status, p, s);
    }

    @PostMapping("/admin/listings/{id}/approve")
    @PreAuthorize("hasAuthority('listing:approve')")
    public ListingView approve(@PathVariable UUID id) {
        return listings.approve(CurrentUser.id(), id);
    }

    @PostMapping("/admin/listings/{id}/reject")
    @PreAuthorize("hasAuthority('listing:reject')")
    public ListingView reject(@PathVariable UUID id, @Valid @RequestBody NoteRequest req) {
        return listings.reject(CurrentUser.id(), id, req.note());
    }

    @PostMapping("/admin/listings/{id}/suspend")
    @PreAuthorize("hasAuthority('listing:suspend')")
    public ListingView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return listings.suspend(CurrentUser.id(), id, req.reasonCode());
    }

    public record ListingRequest(UUID gameAccountId, Long version,
                                 @NotBlank @Size(max = 150) String title,
                                 @Size(max = 5000) String description,
                                 Map<String, Object> attributes,
                                 /** Optional when pricingTiers is given: then derived from the shortest package. */
                                 @Positive Long pricePerHour,
                                 @Size(max = PricingTier.MAX_TIERS) List<@Valid TierRequest> pricingTiers,
                                 /** Ignored when the game has OWNER risks: the deposit is then the sum of riskTerms. */
                                 @Positive Long depositAmount,
                                 /** Deposit per OWNER risk (required) and compensation per RENTER risk (optional). */
                                 @Size(max = 40) List<@Valid RiskTermRequest> riskTerms,
                                 /** Risks the owner adds to this listing; null = keep the current ones. */
                                 @Size(max = CatalogService.MAX_LISTING_RISKS) List<@Valid OwnerRiskRequest> ownerRisks,
                                 boolean autoAccept,
                                 @NotNull EndMode endMode,
                                 /** Minutes before the start a renter can cancel for a full refund; null = keep. */
                                 @PositiveOrZero Integer renterFreeCancelMinutes) {
        ListingService.Content content() {
            List<PricingTier> tiers = pricingTiers == null ? List.of()
                    : pricingTiers.stream().map(t -> new PricingTier(t.durationMinutes(), t.price())).toList();
            List<RiskTerm> terms = riskTerms == null ? List.of()
                    : riskTerms.stream().map(t -> new RiskTerm(t.riskId(), t.amount())).toList();
            return new ListingService.Content(title, description, attributes, pricePerHour == null ? 0 : pricePerHour,
                    tiers, depositAmount == null ? 0 : depositAmount, terms, autoAccept, endMode,
                    ownerRisks == null ? null : ownerRisks.stream().map(r -> new ListingService.OwnerRisk(r.id(),
                            r.side(), r.name(), r.description(), r.complaintWindowMinutes(),
                            r.stackable() == null || r.stackable(), r.amount())).toList(),
                    renterFreeCancelMinutes);
        }
    }

    public record TierRequest(@Positive int durationMinutes, @Positive long price) {}

    public record RiskTermRequest(@NotNull UUID riskId, @PositiveOrZero Long amount) {}

    /** {@code id} null = new risk; {@code amount}: deposit (OWNER) or compensation (RENTER, optional). */
    public record OwnerRiskRequest(UUID id, @NotNull RiskSide side, @NotBlank @Size(max = 120) String name,
                                   @Size(max = 1000) String description, Integer complaintWindowMinutes,
                                   Boolean stackable, @PositiveOrZero Long amount) {}

    public record BusyBlockRequest(@NotNull Instant startAt, @NotNull Instant endAt, @Size(max = 200) String note) {}

    public record NoteRequest(@NotBlank String note) {}

    public record ReasonRequest(@NotBlank String reasonCode) {}

    public record Policy(String handoverMode, int depositHoldHours, int complaintWindowMinutes, int minDurationMinutes,
                         int maxDurationMinutes, int graceMinutes, int renterFreeCancelMinutes, int lateCancelRefundBps,
                         int minAge) {}

    /** {@code risks}: the game's current risks, then the owner's own (ownerDefined); riskTerms hold the amounts. */
    public record ListingDetail(ListingView listing, Policy policy, List<Map<String, Object>> busyPeriods,
                                boolean bookable, List<GameRiskView> risks) {}
}
