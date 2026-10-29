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
import java.util.UUID;

/**
 * Builds the authentication from a verified JWT. Authorities come from the database (via
 * {@link AuthorityResolver}), not from the token's {@code roles} claim: {@code ROLE_<code>} for each role plus
 * one authority per permission code (e.g. {@code listing:approve}).
 */
public class PermissionJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ObjectProvider<AuthorityResolver> resolver;

    public PermissionJwtConverter(ObjectProvider<AuthorityResolver> resolver) {
        this.resolver = resolver;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthorityResolver.Authorities a = resolve(jwt.getSubject());
        List<GrantedAuthority> authorities = new ArrayList<>(a.roles().size() + a.permissions().size());
        a.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        a.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
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
