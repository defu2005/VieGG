package com.rentgg.identity.api;

import com.rentgg.identity.application.UserSearchService;
import com.rentgg.identity.application.UserSearchService.UserBrief;
import com.rentgg.identity.application.UserSearchService.UserHit;
import com.rentgg.shared.web.PageResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * User lookup for every staff role that deals with users, not only those holding user:read: finance needs to
 * know who a withdrawal belongs to, support who opened a dispute, admins whom to grant a role. Profile details
 * and actions stay behind user:read and friends ({@link AdminUserController}).
 */
@RestController
@RequestMapping("/api/v1/staff/users")
@PreAuthorize(StaffUserController.ANY_STAFF_LOOKUP)
public class StaffUserController {

    static final String ANY_STAFF_LOOKUP = "hasAnyAuthority('user:read','role:read','role:assign',"
            + "'withdrawal:list_all','ledger:read','dispute:read_any','order:read_any','listing:review_queue')";

    private final UserSearchService search;

    public StaffUserController(UserSearchService search) {
        this.search = search;
    }

    @GetMapping
    public PageResponse<UserHit> search(@RequestParam(defaultValue = "") String q,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String kycStatus,
                                        @RequestParam(required = false) String riskTier,
                                        @RequestParam(required = false) String role,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return search.search(new UserSearchService.Criteria(q, status, kycStatus, riskTier, role), page, size);
    }

    /** Names for a batch of ids (comma separated, up to 100), e.g. to label the users shown in a table. */
    @GetMapping("/brief")
    public List<UserBrief> brief(@RequestParam List<UUID> ids) {
        return search.briefs(ids);
    }
}
