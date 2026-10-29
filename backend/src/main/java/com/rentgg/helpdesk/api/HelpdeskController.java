package com.rentgg.helpdesk.api;

import com.rentgg.helpdesk.application.HelpdeskService;
import com.rentgg.helpdesk.application.TicketViews.AttachmentData;
import com.rentgg.helpdesk.application.TicketViews.MessageView;
import com.rentgg.helpdesk.application.TicketViews.TicketView;
import com.rentgg.helpdesk.application.TicketViews.UnreadSummary;
import com.rentgg.helpdesk.domain.TicketCategory;
import com.rentgg.helpdesk.domain.TicketStatus;
import com.rentgg.shared.ratelimit.RateLimiter;
import com.rentgg.shared.ratelimit.RateLimiter.Limit;
import com.rentgg.shared.security.CurrentUser;
import com.rentgg.shared.web.PageResponse;
import com.rentgg.shared.web.Paging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Sort;
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
import java.util.Set;
import java.util.UUID;

/** Help desk chat. {@code /tickets}: any signed-in participant · {@code /support/tickets}: help desk staff. */
@RestController
@RequestMapping("/api/v1")
public class HelpdeskController {

    private final HelpdeskService service;
    private final RateLimiter rateLimiter;

    public HelpdeskController(HelpdeskService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    // ------------------------------------------------------------------ participants

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketView open(@Valid @RequestBody OpenRequest req) {
        UUID me = CurrentUser.id();
        rateLimiter.consume("ticket-open:" + me, Limit.perHour(10));
        return service.open(me, req.subject(), req.category(), req.orderId(), req.body());
    }

    @GetMapping("/tickets")
    public PageResponse<TicketView> mine(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.unsorted());
        return PageResponse.of(service.mine(CurrentUser.id(), pageable), x -> x);
    }

    @GetMapping("/tickets/unread")
    public UnreadSummary unread() {
        return service.unread(CurrentUser.id());
    }

    @GetMapping("/tickets/{id}")
    public TicketView get(@PathVariable UUID id) {
        return service.get(CurrentUser.id(), id);
    }

    @GetMapping("/tickets/{id}/messages")
    public List<MessageView> messages(@PathVariable UUID id,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant after) {
        return service.messages(CurrentUser.id(), id, after);
    }

    @PostMapping(value = "/tickets/{id}/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView post(@PathVariable UUID id, @Valid @RequestBody MessageRequest req) {
        UUID me = CurrentUser.id();
        rateLimiter.consume("ticket-msg:" + me, Limit.perMinute(30));
        return service.post(me, id, req.body(), null);
    }

    /** Image message (multipart field "file", optional "body"). */
    @PostMapping(value = "/tickets/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView postImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "body", required = false) String body) throws IOException {
        UUID me = CurrentUser.id();
        rateLimiter.consume("ticket-msg:" + me, Limit.perMinute(30));
        return service.post(me, id, body, file.getBytes());
    }

    @PostMapping("/tickets/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) {
        service.markRead(CurrentUser.id(), id);
    }

    @PostMapping("/tickets/{id}/status")
    public TicketView status(@PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        return service.changeStatus(CurrentUser.id(), id, req.status(), req.note());
    }

    @GetMapping("/tickets/{id}/attachments/{attachmentId}")
    public ResponseEntity<Resource> attachment(@PathVariable UUID id, @PathVariable UUID attachmentId) {
        AttachmentData a = service.attachment(CurrentUser.id(), id, attachmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(a.data()));
    }

    @GetMapping("/orders/{orderId}/tickets")
    public List<TicketView> forOrder(@PathVariable UUID orderId) {
        return service.forOrder(CurrentUser.id(), orderId);
    }

    // ------------------------------------------------------------------ staff

    @GetMapping("/support/tickets")
    @PreAuthorize("hasAuthority('helpdesk:handle')")
    public PageResponse<TicketView> queue(@RequestParam(required = false) Set<TicketStatus> status,
                                          @RequestParam(required = false) TicketCategory category,
                                          @RequestParam(defaultValue = "false") boolean mine,
                                          @RequestParam(defaultValue = "false") boolean unassigned,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        var pageable = Paging.of(page, size, null, Set.of(), Sort.unsorted());
        return PageResponse.of(service.queue(CurrentUser.id(), status, category, mine, unassigned, q, pageable),
                x -> x);
    }

    /** {@code userIds} empty with an order or dispute = invite both renter and owner. */
    @PostMapping("/support/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('helpdesk:handle')")
    public TicketView openByStaff(@Valid @RequestBody StaffOpenRequest req) {
        return service.openByStaff(CurrentUser.id(), req.subject(), req.category(), req.orderId(), req.disputeId(),
                req.userIds(), req.body());
    }

    @PostMapping("/support/tickets/{id}/participants")
    @PreAuthorize("hasAuthority('helpdesk:handle')")
    public TicketView addParticipant(@PathVariable UUID id, @Valid @RequestBody ParticipantRequest req) {
        return service.addParticipant(CurrentUser.id(), id, req.userId());
    }

    @DeleteMapping("/support/tickets/{id}/participants/{userId}")
    @PreAuthorize("hasAuthority('helpdesk:handle')")
    public TicketView removeParticipant(@PathVariable UUID id, @PathVariable UUID userId) {
        return service.removeParticipant(CurrentUser.id(), id, userId);
    }

    /** {@code assigneeId} null = unassign. */
    @PostMapping("/support/tickets/{id}/assign")
    @PreAuthorize("hasAuthority('helpdesk:handle')")
    public TicketView assign(@PathVariable UUID id, @RequestBody AssignRequest req) {
        return service.assign(CurrentUser.id(), id, req.assigneeId());
    }

    public record OpenRequest(@NotBlank @Size(max = 200) String subject, @NotNull TicketCategory category,
                              UUID orderId, @NotBlank @Size(max = 4000) String body) {}

    public record StaffOpenRequest(@NotBlank @Size(max = 200) String subject, TicketCategory category, UUID orderId,
                                   UUID disputeId, @Size(max = 10) List<UUID> userIds,
                                   @NotBlank @Size(max = 4000) String body) {}

    public record MessageRequest(@NotBlank @Size(max = 4000) String body) {}

    public record StatusRequest(@NotNull TicketStatus status, @Size(max = 1000) String note) {}

    public record ParticipantRequest(@NotNull UUID userId) {}

    public record AssignRequest(UUID assigneeId) {}
}
