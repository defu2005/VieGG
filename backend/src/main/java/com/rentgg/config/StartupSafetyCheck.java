package com.rentgg.config;

import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Refuses to start with missing/short secrets, or with dev defaults on staging/prod (System Design §14.3).
 * Mock payment provider and placeholder values are refused on staging/prod too (Dev Guide §2.0).
 */
@Component
public class StartupSafetyCheck {

    static final String DEV_KEY_ID = "devkey";
    static final String DEV_WEBHOOK_SECRET = "dev-only-webhook-secret-rentgg-0123456789";

    /** Substrings that only appear in copy-pasted sample values; matched case-insensitively. */
    static final List<String> PLACEHOLDERS = List.of("changeme", "account_id", "example", "xxx");

    private static final String MOCK_PROVIDER = "FAKE";

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
            // Payment providers call back and redirect to these, so they must be the public HTTPS addresses.
            requireHttps("RENTGG_PUBLIC_BASE_URL", props.payment().publicBaseUrl());
            requireHttps("RENTGG_FRONTEND_BASE_URL", props.payment().frontendBaseUrl());
            String cors = props.security().corsAllowedOrigins();
            if (cors == null || cors.isBlank()) {
                throw new IllegalStateException("RENTGG_CORS_ALLOWED_ORIGINS is required on staging/prod");
            }
            checkPaymentProvider();
            checkObjectStorage();
        }
    }

    /** The default provider must be a real one, and only its own credentials are checked. */
    private void checkPaymentProvider() {
        String raw = props.payment().provider();
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) {
            throw new IllegalStateException("RENTGG_PAYMENT_PROVIDER is required on staging/prod");
        }
        if (MOCK_PROVIDER.equals(code)) {
            throw new IllegalStateException("RENTGG_PAYMENT_PROVIDER must not be FAKE on staging/prod (mock provider)");
        }
        Map<String, String> credentials = providerCredentials(code, props.payment());
        if (credentials == null) {
            throw new IllegalStateException("RENTGG_PAYMENT_PROVIDER must be one of PAYOS, VNPAY, MOMO, ZALOPAY");
        }
        credentials.forEach((name, value) -> requireRealValue(name, value, "RENTGG_PAYMENT_PROVIDER=" + code));
    }

    /** Env var name → configured value for the given provider, or {@code null} when the provider is unknown. */
    static Map<String, String> providerCredentials(String code, RentGgProperties.Payment payment) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (code) {
            case "PAYOS" -> {
                var c = payment.payos();
                values.put("PAYOS_CLIENT_ID", c == null ? null : c.clientId());
                values.put("PAYOS_API_KEY", c == null ? null : c.apiKey());
                values.put("PAYOS_CHECKSUM_KEY", c == null ? null : c.checksumKey());
            }
            case "VNPAY" -> {
                var c = payment.vnpay();
                values.put("VNPAY_TMN_CODE", c == null ? null : c.tmnCode());
                values.put("VNPAY_HASH_SECRET", c == null ? null : c.hashSecret());
            }
            case "MOMO" -> {
                var c = payment.momo();
                values.put("MOMO_PARTNER_CODE", c == null ? null : c.partnerCode());
                values.put("MOMO_ACCESS_KEY", c == null ? null : c.accessKey());
                values.put("MOMO_SECRET_KEY", c == null ? null : c.secretKey());
            }
            case "ZALOPAY" -> {
                var c = payment.zalopay();
                values.put("ZALOPAY_APP_ID", c == null ? null : c.appId());
                values.put("ZALOPAY_KEY1", c == null ? null : c.key1());
                values.put("ZALOPAY_KEY2", c == null ? null : c.key2());
            }
            default -> {
                return null;
            }
        }
        return values;
    }

    /** Object storage is only used when media storage is s3; then every connection field must be real. */
    private void checkObjectStorage() {
        if (!"s3".equalsIgnoreCase(env.getProperty("rentgg.media.storage", "db"))) {
            return;
        }
        requireRealValue("RENTGG_S3_ENDPOINT", env.getProperty("rentgg.media.s3.endpoint"), "RENTGG_MEDIA_STORAGE=s3");
        requireRealValue("RENTGG_S3_BUCKET", env.getProperty("rentgg.media.s3.bucket"), "RENTGG_MEDIA_STORAGE=s3");
        requireRealValue("RENTGG_S3_ACCESS_KEY", env.getProperty("rentgg.media.s3.access-key"), "RENTGG_MEDIA_STORAGE=s3");
        requireRealValue("RENTGG_S3_SECRET_KEY", env.getProperty("rentgg.media.s3.secret-key"), "RENTGG_MEDIA_STORAGE=s3");
    }

    /** Names the variable in the message but never echoes its value. */
    static void requireRealValue(String name, String value, String reason) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required on staging/prod (" + reason + ")");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String placeholder : PLACEHOLDERS) {
            if (lower.contains(placeholder)) {
                throw new IllegalStateException(name + " still holds a placeholder value on staging/prod");
            }
        }
    }

    private static void requireHttps(String name, String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalStateException(name + " must be an https:// address on staging/prod");
        }
    }
}
