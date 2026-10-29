package com.rentgg.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Refuses to start with missing/short secrets, or with dev defaults on staging/prod (System Design §14.3).
 */
@Component
public class StartupSafetyCheck {

    static final String DEV_KEY_ID = "devkey";
    static final String DEV_WEBHOOK_SECRET = "dev-only-webhook-secret-rentgg-0123456789";

    private final RentGgProperties props;
    private final Environment env;

    public StartupSafetyCheck(RentGgProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    @PostConstruct
    void check() {
        if (env.acceptsProfiles(Profiles.of("dev")) && env.acceptsProfiles(Profiles.of("prod", "staging"))) {
            throw new IllegalStateException("Profile 'dev' must not be combined with staging/prod");
        }
        String jwt = props.security().jwtSecret();
        if (jwt == null || jwt.isBlank()) {
            throw new IllegalStateException("RENTGG_JWT_SECRET is required");
        }
        if (Base64.getDecoder().decode(jwt).length < 32) {
            throw new IllegalStateException("RENTGG_JWT_SECRET must decode to at least 32 bytes");
        }
        String webhook = props.payment().webhookSecret();
        if (webhook == null || webhook.length() < 32) {
            throw new IllegalStateException("RENTGG_PAYMENT_WEBHOOK_SECRET must be at least 32 characters");
        }
        if (env.acceptsProfiles(Profiles.of("prod", "staging"))) {
            if (DEV_KEY_ID.equals(props.credentials().activeKeyId())
                    || props.credentials().keys().contains(DEV_KEY_ID + ":")
                    || DEV_WEBHOOK_SECRET.equals(webhook)) {
                throw new IllegalStateException("Dev secrets must not be used on staging/prod");
            }
            if (env.acceptsProfiles(Profiles.of("prod")) && props.payment().fakeEnabled()) {
                throw new IllegalStateException("RENTGG_PAYMENT_FAKE_ENABLED must be false on prod");
            }
        }
    }
}
