package com.rentgg.listing.api;

import com.rentgg.listing.application.ListingAppealService;
import com.rentgg.listing.application.ListingAppealService.Appeal;
import com.rentgg.listing.application.ListingService;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** UC-05: the listing owner appeals a rejection or suspension; a staff member decides the appeal. */
@RestController
@RequestMapping("/api/v1")
public class ListingAppealController {

    private final ListingAppealService appeals;
    private final ListingService listings;

    public ListingAppealController(ListingAppealService appeals, ListingService listings) {
        this.appeals = appeals;
        this.listings = listings;
    }

    public record AppealRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ReviewRequest(@NotNull Boolean accept, @Size(max = 1000) String note) {}

    @GetMapping("/listings/{id}/decisions")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public List<Map<String, Object>> decisions(@PathVariable UUID id) {
        return listings.decisionsOf(CurrentUser.id(), id);
    }

    @PostMapping("/listing-decisions/{id}/appeals")
    @PreAuthorize("hasAuthority('listing:manage_own')")
    public Appeal file(@PathVariable UUID id, @Valid @RequestBody AppealRequest req) {
        return appeals.file(CurrentUser.id(), id, req.reason());
    }

    @GetMapping("/support/listing-decisions/{id}/appeals")
    @PreAuthorize("hasAuthority('listing:appeal_review')")
    public List<Appeal> forDecision(@PathVariable UUID id) {
        return appeals.forDecision(id);
    }

    @PostMapping("/support/listing-appeals/{id}/review")
    @PreAuthorize("hasAuthority('listing:appeal_review')")
    public Appeal review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest req) {
        return appeals.review(CurrentUser.id(), id, req.accept().booleanValue(), req.note());
    }
}
