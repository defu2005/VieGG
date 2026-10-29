package com.rentgg.dispute.api;

import com.rentgg.dispute.application.DisputeService;
import com.rentgg.dispute.application.DisputeView;
import com.rentgg.dispute.domain.DisputeStatus;
import com.rentgg.dispute.domain.DisputeType;
import com.rentgg.identity.domain.UserStatus;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.web.PageResponse;
import com.rentgg.shared.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Sort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DisputeController {

    private final DisputeService disputes;

    public DisputeController(DisputeService disputes) {
        this.disputes = disputes;
    }

    @PostMapping("/orders/{orderId}/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('dispute:open')")
    public DisputeView open(@PathVariable UUID orderId, @Valid @RequestBody OpenRequest req) {
        List<UUID> riskIds = req.riskIds() != null && !req.riskIds().isEmpty() ? req.riskIds()
                : req.riskId() == null ? List.of() : List.of(req.riskId());
        return disputes.open(CurrentUser.id(), orderId, req.type(), riskIds, req.description(), req.evidenceUrls());
    }

    @GetMapping("/orders/{orderId}/disputes")
    public List<DisputeView> forOrder(@PathVariable UUID orderId) {
        return disputes.forOrder(orderId).stream()
                .filter(d -> canSee(d.id())).toList();
    }

    @GetMapping("/disputes/{id}")
    public DisputeView get(@PathVariable UUID id) {
        return disputes.getForCaller(CurrentUser.id(), id);
    }

    @PostMapping("/disputes/{id}/evidence")
    @PreAuthorize("hasAuthority('dispute:open')")
    public DisputeView addEvidence(@PathVariable UUID id, @Valid @RequestBody EvidenceRequest req) {
        return disputes.addEvidence(CurrentUser.id(), id, req.evidenceUrls(), req.note());
    }

    /** Photo or video evidence (multipart field "file", optional "note"). */
    @PostMapping(value = "/disputes/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('dispute:open')")
    public DisputeView addAttachment(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "note", required = false) String note) throws IOException {
        return disputes.addAttachment(CurrentUser.id(), id, file.getBytes(), note);
    }

    /** Served as a Resource so browsers can seek in videos (HTTP Range). */
    @GetMapping("/disputes/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        DisputeService.AttachmentData a = disputes.attachment(CurrentUser.id(), id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(a.data()));
    }

    @GetMapping("/support/disputes")
    @PreAuthorize("hasAuthority('dispute:read_any')")
    public PageResponse<DisputeView> search(@RequestParam(required = false) String q,
                                            @RequestParam(required = false) DisputeStatus status,
                                            @RequestParam(required = false) DisputeType type,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.by(Sort.Direction.ASC, "deadlineAt"));
        return PageResponse.of(disputes.search(q, status, type, pageable), x -> x);
    }

    @PostMapping("/support/disputes/{id}/resolve")
    @PreAuthorize("hasAuthority('dispute:resolve')")
    public DisputeView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest req) {
        return disputes.resolve(CurrentUser.id(), id, new DisputeService.Decision("RESOLVE".equals(req.decision()),
                req.depositDeduction() == null ? 0 : req.depositDeduction(),
                req.rentRefund() == null ? 0 : req.rentRefund(), req.compensation() == null ? 0 : req.compensation(),
                req.reasonCode(), req.note(), req.renterStatus()));
    }

    /** One risk of a renter complaint; the last decision closes the complaint and pays automatically. */
    @PostMapping("/support/disputes/{id}/items/{itemId}/decide")
    @PreAuthorize("hasAuthority('dispute:resolve')")
    public DisputeView decideItem(@PathVariable UUID id, @PathVariable UUID itemId,
                                  @Valid @RequestBody ItemDecisionRequest req) {
        return disputes.decideItem(CurrentUser.id(), id, itemId, "ACCEPT".equals(req.decision()), req.reasonCode(),
                req.note());
    }

    private boolean canSee(UUID disputeId) {
        try {
            disputes.getForCaller(CurrentUser.id(), disputeId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * {@code riskIds} (or the older single {@code riskId}): risks of the order's policy snapshot; OWNER_RISK takes
     * exactly one, RENTER_RISK one or more.
     */
    public record OpenRequest(@NotNull DisputeType type, UUID riskId, @Size(max = 20) List<UUID> riskIds, @NotBlank @Size(max = 4000) String description,
                              @Size(max = 20) List<String> evidenceUrls) {}

    public record ItemDecisionRequest(@NotBlank @Pattern(regexp = "ACCEPT|REJECT") String decision,
                                      @NotBlank String reasonCode, @Size(max = 4000) String note) {}

    public record EvidenceRequest(@Size(max = 20) List<String> evidenceUrls, @Size(max = 4000) String note) {}

    public record ResolveRequest(@NotBlank @Pattern(regexp = "RESOLVE|REJECT") String decision,
                                 @PositiveOrZero Long depositDeduction, @PositiveOrZero Long rentRefund,
                                 @PositiveOrZero Long compensation,
                                 @NotBlank String reasonCode, @Size(max = 4000) String note, UserStatus renterStatus) {}
}
