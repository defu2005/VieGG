package com.rentgg.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Application-level settings (prefix {@code rentgg}), see System Design §14.2. */
@ConfigurationProperties(prefix = "rentgg")
public record RentGgProperties(
        Order order,
        Rotation rotation,
        Credentials credentials,
        Security security,
        RateLimit rateLimit,
        Approval approval,
        Dispute dispute,
        Jobs jobs,
        Outbox outbox,
        Payment payment) {

    public record Order(int paymentHoldMinutes, int ownerApprovalMinutes, int endingReminderMinutes,
                        int otpNoteTtlMinutes) {}

    public record Rotation(int slaMinutes) {}

    public record Credentials(int rateLimitPerHour, boolean requireReauth, String keys, String activeKeyId) {}

    public record Security(String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl,
                           String corsAllowedOrigins) {}

    public record RateLimit(int loginPerMinute, int loginPerHour, int registerPerHour, int otpPerHour,
                            int ordersPerHour) {}

    public record Approval(Duration ttl) {}

    public record Dispute(Duration resolutionSla, Duration takeoverResolutionSla) {}

    public record Jobs(boolean enabled, String cron, int batchSize) {}

    public record Outbox(int maxAttempts) {}

    /**
     * {@code provider}: default provider for top-ups. Each real provider is offered once its credentials are set;
     * the FAKE one only when {@code fakeEnabled}.
     */
    public record Payment(String provider, String webhookSecret, String publicBaseUrl, String frontendBaseUrl,
                          boolean fakeEnabled, Vnpay vnpay, Momo momo, Zalopay zalopay, Payos payos) {

        public record Vnpay(String tmnCode, String hashSecret, String payUrl) {}

        public record Momo(String partnerCode, String accessKey, String secretKey, String endpoint) {}

        public record Zalopay(String appId, String key1, String key2, String endpoint) {}

        public record Payos(String clientId, String apiKey, String checksumKey, String endpoint) {}
    }
}
