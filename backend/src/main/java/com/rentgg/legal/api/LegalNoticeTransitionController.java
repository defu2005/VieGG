package com.rentgg.legal.api;

import com.rentgg.legal.application.LegalNoticeService;
import com.rentgg.legal.application.LegalNoticeService.Notice;
import com.rentgg.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** UC-20: staff move a legal notice from OPEN to RESPONDED and then to CLOSED. */
@RestController
@RequestMapping("/api/v1/support/legal-notices")
public class LegalNoticeTransitionController {

    private final LegalNoticeService notices;

    public LegalNoticeTransitionController(LegalNoticeService notices) {
        this.notices = notices;
    }

    public record TransitionRequest(@NotBlank String to, @Size(max = 1000) String note) {}

    @PostMapping("/{id}/transition")
    @PreAuthorize("hasAuthority('game:change_tos')")
    public Notice transition(@PathVariable UUID id, @Valid @RequestBody TransitionRequest req) {
        return notices.transition(CurrentUser.id(), id, req.to(), req.note());
    }
}
