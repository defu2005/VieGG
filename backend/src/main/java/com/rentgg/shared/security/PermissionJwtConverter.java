package com.rentgg.shared.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the authentication from a verified JWT. Authorities come from the database (via
 * {@link AuthorityResolver}), not from the token's {@code roles} claim: {@code ROLE_<code>} for each role plus
 * one authority per permission code (e.g. {@code listing:approve}).
 */
public class PermissionJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ObjectProvider<AuthorityResolver> resolver;
    private final boolean staffMfaRequired;

    public PermissionJwtConverter(ObjectProvider<AuthorityResolver> resolver, boolean staffMfaRequired) {
        this.resolver = resolver;
        this.staffMfaRequired = staffMfaRequired;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthorityResolver.Authorities a = resolve(jwt.getSubject());
        // Staff without a verified second factor keep their roles but get no permissions (Dev Guide §2.4).
        boolean mfa = Boolean.TRUE.equals(jwt.getClaimAsBoolean("mfa"));
        Set<String> permissions = a.staff() && staffMfaRequired && !mfa ? Set.of() : a.permissions();
        List<GrantedAuthority> authorities = new ArrayList<>(a.roles().size() + permissions.size());
        a.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    private AuthorityResolver.Authorities resolve(String subject) {
        UUID userId;
        try {
            userId = UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            return AuthorityResolver.Authorities.NONE;
        }
        AuthorityResolver r = resolver.getIfAvailable();
        return r == null ? AuthorityResolver.Authorities.NONE : r.resolve(userId);
    }
}
