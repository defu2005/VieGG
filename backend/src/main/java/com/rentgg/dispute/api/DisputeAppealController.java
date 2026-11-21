package com.rentgg.dispute.api;

import com.rentgg.dispute.application.DisputeAppealService;
import com.rentgg.dispute.application.DisputeAppealService.Appeal;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DisputeAppealController {

    private final DisputeAppealService appeals;

    public DisputeAppealController(DisputeAppealService appeals) {
        this.appeals = appeals;
    }

    public record AppealRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record ReviewRequest(@NotNull Boolean accept, @Size(max = 1000) String note) {}

    @PostMapping("/disputes/{id}/appeal")
    @PreAuthorize("hasAuthority('dispute:open')")
    public Appeal file(@PathVariable UUID id, @Valid @RequestBody AppealRequest req) {
        return appeals.file(CurrentUser.id(), id, req.reason());
    }

    /** Support decides a pending appeal: accept (the decision should be made again) or dismiss (with a note). */
    @PostMapping("/support/dispute-appeals/{id}/review")
    @PreAuthorize("hasAuthority('dispute:resolve')")
    public Appeal review(@PathVariable UUID id, @Valid @RequestBody ReviewRequest req) {
        return appeals.review(CurrentUser.id(), id, req.accept().booleanValue(), req.note());
    }

    @GetMapping("/support/disputes/{id}/appeals")
    @PreAuthorize("hasAuthority('dispute:read_any')")
    public List<Appeal> list(@PathVariable UUID id) {
        return appeals.forDispute(id);
    }
}
