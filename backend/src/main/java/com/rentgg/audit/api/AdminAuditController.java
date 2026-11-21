package com.rentgg.audit.api;

import com.rentgg.audit.application.AuditQueryService;
import com.rentgg.audit.application.AuditQueryService.Entry;
import com.rentgg.shared.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AuditQueryService audit;

    public AdminAuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    /** Filters: entityType, entityId, actorId, action (all optional). Newest first, at most 100 per page. */
    @GetMapping
    @PreAuthorize("hasAuthority('audit:read')")
    public PageResponse<Entry> search(@RequestParam(required = false) String entityType,
                                      @RequestParam(required = false) UUID entityId,
                                      @RequestParam(required = false) UUID actorId,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return audit.search(new AuditQueryService.Filter(entityType, entityId, actorId, action), page, size);
    }
}
