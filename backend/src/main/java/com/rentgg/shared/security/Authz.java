package com.rentgg.shared.security;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Helpers for {@code @PreAuthorize} expressions that {@code hasAuthority} cannot express, used as
 * {@code @PreAuthorize("@authz.anyWithPrefix(authentication, 'approval:decide:')")}.
 */
@Component("authz")
public class Authz {

    /** True when the caller holds at least one permission starting with {@code prefix}. */
    public boolean anyWithPrefix(Authentication authentication, String prefix) {
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().startsWith(prefix));
    }
}
