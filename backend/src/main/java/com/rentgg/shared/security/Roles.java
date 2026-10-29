package com.rentgg.shared.security;

/**
 * Codes of the built-in (system) roles. Roles are data ({@code roles} table) and more can be created at runtime;
 * authorization checks must use {@link Permission}, not these names. They remain for seeding, notification
 * routing and the base role every user gets.
 */
public final class Roles {
    public static final String USER = "USER";
    public static final String SUPPORT = "SUPPORT";
    public static final String ADMIN = "ADMIN";
    public static final String FINANCE = "FINANCE";

    private Roles() {}
}
