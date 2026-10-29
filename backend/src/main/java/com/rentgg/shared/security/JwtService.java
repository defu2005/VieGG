package com.rentgg.shared.security;

import com.rentgg.config.RentGgProperties;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Issues access tokens: JWT HS256, 15 minutes, claims sub/roles/status/iat/exp/jti (§12.1). */
@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final RentGgProperties props;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, RentGgProperties props, Clock clock) {
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(UUID userId, Collection<String> roles, String status) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.security().accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("rentgg")
                .subject(userId.toString())
                .claim("roles", List.copyOf(roles))
                .claim("status", status)
                .issuedAt(now)
                .expiresAt(exp)
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, exp);
    }

    public record IssuedToken(String value, Instant expiresAt) {}
}
