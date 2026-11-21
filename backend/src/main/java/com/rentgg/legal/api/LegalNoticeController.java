package com.rentgg.legal.api;

import com.rentgg.legal.application.LegalNoticeService;
import com.rentgg.legal.application.LegalNoticeService.Notice;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/support/legal-notices")
public class LegalNoticeController {

    private final LegalNoticeService notices;

    public LegalNoticeController(LegalNoticeService notices) {
        this.notices = notices;
    }

    public record RecordRequest(UUID gameId,
                                @NotBlank @Size(max = 200) String publisherName,
                                @Size(max = 100) String referenceNo,
                                @NotBlank @Size(max = 300) String subject,
                                @NotBlank @Size(max = 10_000) String body,
                                Instant receivedAt) {}

    @PostMapping
    @PreAuthorize("hasAuthority('game:change_tos')")
    public Notice record(@Valid @RequestBody RecordRequest req) {
        return notices.record(CurrentUser.id(), new LegalNoticeService.Command(req.gameId(), req.publisherName(),
                req.referenceNo(), req.subject(), req.body(), req.receivedAt()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('game:change_tos')")
    public Notice get(@PathVariable UUID id) {
        return notices.get(id);
    }

    /** Filter by status (OPEN, RESPONDED, CLOSED); sorted by response deadline. */
    @GetMapping
    @PreAuthorize("hasAuthority('game:change_tos')")
    public PageResponse<Notice> search(@RequestParam(required = false) String status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return notices.search(status, page, size);
    }
}
