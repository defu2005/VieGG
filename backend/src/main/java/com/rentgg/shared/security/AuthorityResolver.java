package com.rentgg.shared.security;

import java.util.Set;
import java.util.UUID;

/**
 * Port implemented by the identity module: the roles and permissions a user holds right now. Read on every
 * authenticated request (cached), so revoking a role takes effect without waiting for the access token to expire.
 */
public interface AuthorityResolver {

    Authorities resolve(UUID userId);

    /**
     * @param staff true when the user holds at least one staff role; staff accounts do not get the permissions of
     *              non-staff roles such as the customer role USER.
     */
    record Authorities(Set<String> roles, Set<String> permissions, boolean staff) {
        public static final Authorities NONE = new Authorities(Set.of(), Set.of(), false);
    }
}
