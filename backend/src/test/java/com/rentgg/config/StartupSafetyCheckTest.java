package com.rentgg.config;

import com.rentgg.config.RentGgProperties.Approval;
import com.rentgg.config.RentGgProperties.Credentials;
import com.rentgg.config.RentGgProperties.Dispute;
import com.rentgg.config.RentGgProperties.Jobs;
import com.rentgg.config.RentGgProperties.Order;
import com.rentgg.config.RentGgProperties.Outbox;
import com.rentgg.config.RentGgProperties.Payment;
import com.rentgg.config.RentGgProperties.RateLimit;
import com.rentgg.config.RentGgProperties.Rotation;
import com.rentgg.config.RentGgProperties.Security;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link StartupSafetyCheck}: no Spring context, no database, no real PSP.
 * The environment and properties are built in memory, mirroring what application.yml binds from the env vars.
 */
class StartupSafetyCheckTest {

    /** Valid prod/staging fixture. Every value is realistic and contains no placeholder text. */
    private static Map<String, String> validVars() {
        Map<String, String> v = new HashMap<>();
        v.put("RENTGG_JWT_SECRET", Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
        v.put("RENTGG_PAYMENT_WEBHOOK_SECRET", "webhook-secret-for-tests-0123456789abcdef");
        v.put("RENTGG_CREDENTIAL_ACTIVE_KEY_ID", "k202611");
        v.put("RENTGG_CREDENTIAL_KEYS", "k202611:" + Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
        v.put("RENTGG_CORS_ALLOWED_ORIGINS", "https://rentgg.vn");
        v.put("RENTGG_PUBLIC_BASE_URL", "https://rentgg.vn");
        v.put("RENTGG_FRONTEND_BASE_URL", "https://rentgg.vn");
        v.put("RENTGG_PAYMENT_PROVIDER", "PAYOS");
        v.put("RENTGG_PAYMENT_FAKE_ENABLED", "false");
        v.put("PAYOS_CLIENT_ID", "3f8c2a91-client-id");
        v.put("PAYOS_API_KEY", "a1b2c3d4-api-key");
        v.put("PAYOS_CHECKSUM_KEY", "9f8e7d6c-checksum-key");
        v.put("VNPAY_TMN_CODE", "RENTGGTM");
        v.put("VNPAY_HASH_SECRET", "VNPHASHSECRET123456");
        v.put("MOMO_PARTNER_CODE", "MOMOPARTNER01");
        v.put("MOMO_ACCESS_KEY", "momoAccessKey01");
        v.put("MOMO_SECRET_KEY", "momoSecretKey0123");
        v.put("ZALOPAY_APP_ID", "554");
        v.put("ZALOPAY_KEY1", "zaloKey1Value0123");
        v.put("ZALOPAY_KEY2", "zaloKey2Value0123");
        v.put("RENTGG_MEDIA_STORAGE", "s3");
        v.put("RENTGG_S3_ENDPOINT", "https://f00dcafe1234.r2.cloudflarestorage.com");
        v.put("RENTGG_S3_BUCKET", "rentgg-media");
        v.put("RENTGG_S3_ACCESS_KEY", "r2AccessKey0123456");
        v.put("RENTGG_S3_SECRET_KEY", "r2SecretKey0123456789");
        return v;
    }

    private static StartupSafetyCheck check(Map<String, String> v, String... profiles) {
        Payment payment = new Payment(
                v.get("RENTGG_PAYMENT_PROVIDER"),
                v.get("RENTGG_PAYMENT_WEBHOOK_SECRET"),
                v.get("RENTGG_PUBLIC_BASE_URL"),
                v.get("RENTGG_FRONTEND_BASE_URL"),
                Boolean.parseBoolean(v.get("RENTGG_PAYMENT_FAKE_ENABLED")),
                new Payment.Vnpay(v.get("VNPAY_TMN_CODE"), v.get("VNPAY_HASH_SECRET"), "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"),
                new Payment.Momo(v.get("MOMO_PARTNER_CODE"), v.get("MOMO_ACCESS_KEY"), v.get("MOMO_SECRET_KEY"), "https://test-payment.momo.vn"),
                new Payment.Zalopay(v.get("ZALOPAY_APP_ID"), v.get("ZALOPAY_KEY1"), v.get("ZALOPAY_KEY2"), "https://sb-openapi.zalopay.vn"),
                new Payment.Payos(v.get("PAYOS_CLIENT_ID"), v.get("PAYOS_API_KEY"), v.get("PAYOS_CHECKSUM_KEY"), "https://api-merchant.payos.vn"));
        RentGgProperties props = new RentGgProperties(
                new Order(30, 60, 30, 10),
                new Rotation(60),
                new Credentials(10, true, v.get("RENTGG_CREDENTIAL_KEYS"), v.get("RENTGG_CREDENTIAL_ACTIVE_KEY_ID")),
                new Security(v.get("RENTGG_JWT_SECRET"), Duration.ofMinutes(15), Duration.ofDays(30), v.get("RENTGG_CORS_ALLOWED_ORIGINS")),
                new RateLimit(10, 100, 10, 5, 20),
                new Approval(Duration.ofMinutes(30)),
                new Dispute(Duration.ofHours(72), Duration.ofHours(4)),
                new Jobs(true, "0 * * * * *", 100),
                new Outbox(10),
                payment);

        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        // Mirror how application.yml binds the S3 settings; an unset variable binds to an empty string.
        env.setProperty("rentgg.media.storage", v.getOrDefault("RENTGG_MEDIA_STORAGE", "db"));
        env.setProperty("rentgg.media.s3.endpoint", v.getOrDefault("RENTGG_S3_ENDPOINT", ""));
        env.setProperty("rentgg.media.s3.bucket", v.getOrDefault("RENTGG_S3_BUCKET", ""));
        env.setProperty("rentgg.media.s3.access-key", v.getOrDefault("RENTGG_S3_ACCESS_KEY", ""));
        env.setProperty("rentgg.media.s3.secret-key", v.getOrDefault("RENTGG_S3_SECRET_KEY", ""));
        return new StartupSafetyCheck(props, env);
    }

    private static void assertRefused(Map<String, String> v, String profile, String expectedInMessage) {
        assertRefused(v, new String[] {profile}, expectedInMessage);
    }

    private static void assertRefused(Map<String, String> v, String[] profiles, String expectedInMessage) {
        assertThatThrownBy(() -> check(v, profiles).check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedInMessage);
    }

    // ---- Positive controls

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void validConfigStarts(String profile) {
        assertThatCode(() -> check(validVars(), profile).check()).doesNotThrowAnyException();
    }

    @Test
    void devProfileWithMockProviderStarts() {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", "FAKE");
        v.put("RENTGG_PAYMENT_FAKE_ENABLED", "true");
        v.put("RENTGG_MEDIA_STORAGE", "db");
        v.put("RENTGG_CORS_ALLOWED_ORIGINS", "");
        v.put("RENTGG_PUBLIC_BASE_URL", "http://localhost:8080");
        assertThatCode(() -> check(v, "dev").check()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void dbMediaStorageDoesNotRequireS3(String profile) {
        Map<String, String> v = validVars();
        v.put("RENTGG_MEDIA_STORAGE", "db");
        v.put("RENTGG_S3_ENDPOINT", "");
        v.put("RENTGG_S3_BUCKET", "");
        v.put("RENTGG_S3_ACCESS_KEY", "");
        v.put("RENTGG_S3_SECRET_KEY", "");
        assertThatCode(() -> check(v, profile).check()).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"payos", " PAYOS ", "Payos"})
    void providerNameIsNormalisedLikeTheRegistry(String provider) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", provider);
        assertThatCode(() -> check(v, "prod").check()).doesNotThrowAnyException();
    }

    @Test
    void onlySelectedProviderKeysAreChecked() {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", "PAYOS");
        v.put("VNPAY_TMN_CODE", "");
        v.put("MOMO_SECRET_KEY", "");
        assertThatCode(() -> check(v, "prod").check()).doesNotThrowAnyException();
    }

    // ---- Mock provider (Dev Guide §2.0: FAKE must be refused even when fake-enabled is false)

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void fakeProviderRefusedWhenFakeDisabled(String profile) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", "FAKE");
        v.put("RENTGG_PAYMENT_FAKE_ENABLED", "false");
        assertRefused(v, profile, "RENTGG_PAYMENT_PROVIDER must not be FAKE");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAKE", "fake", " Fake "})
    void fakeProviderRefusedRegardlessOfCaseOrSpaces(String provider) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", provider);
        assertRefused(v, "staging", "RENTGG_PAYMENT_PROVIDER must not be FAKE");
    }

    @Test
    void fakeEnabledStillRefusedOnProd() {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_FAKE_ENABLED", "true");
        assertRefused(v, "prod", "RENTGG_PAYMENT_FAKE_ENABLED must be false on prod");
    }

    // ---- Provider selection

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankProviderRefused(String provider) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", provider);
        assertRefused(v, "prod", "RENTGG_PAYMENT_PROVIDER is required");
    }

    @Test
    void missingProviderRefused() {
        Map<String, String> v = validVars();
        v.remove("RENTGG_PAYMENT_PROVIDER");
        assertRefused(v, "prod", "RENTGG_PAYMENT_PROVIDER is required");
    }

    @Test
    void unknownProviderRefused() {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", "STRIPE");
        assertRefused(v, "prod", "must be one of PAYOS, VNPAY, MOMO, ZALOPAY");
    }

    // ---- Provider credentials: every key of every provider, empty and placeholder

    static Stream<Arguments> providerKeys() {
        return Stream.of(
                Arguments.of("PAYOS", "PAYOS_CLIENT_ID"),
                Arguments.of("PAYOS", "PAYOS_API_KEY"),
                Arguments.of("PAYOS", "PAYOS_CHECKSUM_KEY"),
                Arguments.of("VNPAY", "VNPAY_TMN_CODE"),
                Arguments.of("VNPAY", "VNPAY_HASH_SECRET"),
                Arguments.of("MOMO", "MOMO_PARTNER_CODE"),
                Arguments.of("MOMO", "MOMO_ACCESS_KEY"),
                Arguments.of("MOMO", "MOMO_SECRET_KEY"),
                Arguments.of("ZALOPAY", "ZALOPAY_APP_ID"),
                Arguments.of("ZALOPAY", "ZALOPAY_KEY1"),
                Arguments.of("ZALOPAY", "ZALOPAY_KEY2"));
    }

    @ParameterizedTest
    @MethodSource("providerKeys")
    void emptyProviderKeyRefused(String provider, String varName) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", provider);
        v.put(varName, "");
        assertRefused(v, "prod", varName + " is required");
    }

    @ParameterizedTest
    @MethodSource("providerKeys")
    void missingProviderKeyRefused(String provider, String varName) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", provider);
        v.remove(varName);
        assertRefused(v, "staging", varName + " is required");
    }

    @ParameterizedTest
    @MethodSource("providerKeys")
    void placeholderProviderKeyRefused(String provider, String varName) {
        Map<String, String> v = validVars();
        v.put("RENTGG_PAYMENT_PROVIDER", provider);
        v.put(varName, "replace-with-changeme");
        assertRefused(v, "prod", varName + " still holds a placeholder value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"changeme", "CHANGEME", "ACCOUNT_ID", "your-example-key", "xxx", "abcXXXdef"})
    void placeholderTextRefusedInAnyCase(String placeholder) {
        Map<String, String> v = validVars();
        v.put("PAYOS_API_KEY", placeholder);
        assertRefused(v, "prod", "PAYOS_API_KEY still holds a placeholder value");
    }

    @Test
    void refusalMessageDoesNotEchoTheSecretValue() {
        Map<String, String> v = validVars();
        v.put("PAYOS_API_KEY", "changeme-SECRETVALUE-123");
        assertThatThrownBy(() -> check(v, "prod").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYOS_API_KEY")
                .hasMessageNotContaining("SECRETVALUE");
    }

    // ---- Object storage (Dev Guide §2.0: S3 fields required when media storage is s3)

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void s3StorageRefusedWithoutEndpoint(String profile) {
        Map<String, String> v = validVars();
        v.put("RENTGG_S3_ENDPOINT", "");
        assertRefused(v, profile, "RENTGG_S3_ENDPOINT is required");
    }

    @Test
    void s3StorageRefusedWithoutBucket() {
        Map<String, String> v = validVars();
        v.remove("RENTGG_S3_BUCKET");
        assertRefused(v, "prod", "RENTGG_S3_BUCKET is required");
    }

    @Test
    void s3StorageRefusedWithoutAccessKey() {
        Map<String, String> v = validVars();
        v.put("RENTGG_S3_ACCESS_KEY", "  ");
        assertRefused(v, "prod", "RENTGG_S3_ACCESS_KEY is required");
    }

    @Test
    void s3StorageRefusedWithoutSecretKey() {
        Map<String, String> v = validVars();
        v.put("RENTGG_S3_SECRET_KEY", "");
        assertRefused(v, "staging", "RENTGG_S3_SECRET_KEY is required");
    }

    @Test
    void s3EndpointTemplateWithAccountIdRefused() {
        Map<String, String> v = validVars();
        v.put("RENTGG_S3_ENDPOINT", "https://ACCOUNT_ID.r2.cloudflarestorage.com");
        assertRefused(v, "prod", "RENTGG_S3_ENDPOINT still holds a placeholder value");
    }

    @Test
    void s3SecretKeyPlaceholderRefused() {
        Map<String, String> v = validVars();
        v.put("RENTGG_S3_SECRET_KEY", "xxxxxxxxxxxxxxxxxxxx");
        assertRefused(v, "prod", "RENTGG_S3_SECRET_KEY still holds a placeholder value");
    }

    // ---- Existing rules still hold alongside the new ones

    @Test
    void stagingRefusesHttpPublicUrl() {
        Map<String, String> v = validVars();
        v.put("RENTGG_PUBLIC_BASE_URL", "http://rentgg.vn");
        assertRefused(v, "staging", "RENTGG_PUBLIC_BASE_URL must be an https:// address");
    }

    @Test
    void devProfileCannotBeCombinedWithProd() {
        assertRefused(validVars(), new String[] {"dev", "prod"}, "must not be combined");
    }
    // ---- Secrets: dev default, placeholder text and single repeated character (advisor-specified, Dev Guide §2.0)

    static final String DEV_JWT = "ZGV2LW9ubHktand0LXNlY3JldC1yZW50Z2ctMzItYnl0ZXMtbWluIQ==";

    @ParameterizedTest
    @ValueSource(strings = {"prod", "staging"})
    void devJwtDefaultRefused(String profile) {
        Map<String, String> v = validVars();
        v.put("RENTGG_JWT_SECRET", DEV_JWT);
        assertRefused(v, profile, "RENTGG_JWT_SECRET must not be the dev default on staging/prod");
    }

    @Test
    void devJwtDefaultAcceptedOnDevProfile() {
        Map<String, String> v = validVars();
        v.put("RENTGG_JWT_SECRET", DEV_JWT);
        v.put("RENTGG_PAYMENT_PROVIDER", "FAKE");
        v.put("RENTGG_PAYMENT_FAKE_ENABLED", "true");
        v.put("RENTGG_MEDIA_STORAGE", "db");
        assertThatCode(() -> check(v, "dev").check()).doesNotThrowAnyException();
    }

    static Stream<Arguments> placeholderSecrets() {
        String jwtChangeme = "changeme".repeat(6);       // 48 base64 chars = 36 bytes
        String jwtExample = "example".repeat(8);         // 56 base64 chars = 42 bytes
        return Stream.of(
                Arguments.of("RENTGG_JWT_SECRET", jwtChangeme),
                Arguments.of("RENTGG_JWT_SECRET", jwtExample),
                Arguments.of("RENTGG_PAYMENT_WEBHOOK_SECRET", "changeme-changeme-changeme-changeme-x"),
                Arguments.of("RENTGG_PAYMENT_WEBHOOK_SECRET", "my-example-webhook-secret-0123456789"),
                Arguments.of("RENTGG_PAYMENT_WEBHOOK_SECRET", "ACCOUNT_ID-webhook-secret-0123456789"),
                Arguments.of("RENTGG_CREDENTIAL_KEYS", "k202611:changeme-changeme-changeme-changeme"),
                Arguments.of("RENTGG_CREDENTIAL_KEYS", "k202611:example-keyvalue-0123456789abcdef"),
                Arguments.of("RENTGG_CREDENTIAL_KEYS", "k202611:ACCOUNT_ID-keyvalue-0123456789abcd"));
    }

    @ParameterizedTest
    @MethodSource("placeholderSecrets")
    void placeholderSecretRefused(String varName, String value) {
        Map<String, String> v = validVars();
        v.put(varName, value);
        assertThatThrownBy(() -> check(v, "prod").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(varName + " still holds a placeholder value")
                .hasMessageNotContaining(value);
    }

    @Test
    void singleRepeatedCharacterSecretsRefused() {
        Map<String, String> jwt = validVars();
        jwt.put("RENTGG_JWT_SECRET", "x".repeat(44));    // decodes to 33 bytes, but one distinct character
        assertRefused(jwt, "prod", "RENTGG_JWT_SECRET still holds a placeholder value");

        Map<String, String> webhook = validVars();
        webhook.put("RENTGG_PAYMENT_WEBHOOK_SECRET", "x".repeat(36));
        assertRefused(webhook, "staging", "RENTGG_PAYMENT_WEBHOOK_SECRET still holds a placeholder value");
    }

    @Test
    void mixedCaseXxxIsNotAPlaceholderForSecrets() {
        // "xxx" is excluded on purpose for secrets: random base64 contains it now and then.
        Map<String, String> v = validVars();
        v.put("RENTGG_JWT_SECRET", Base64.getEncoder().encodeToString("0123456789xXx0123456789abcdef!!!".getBytes()));
        v.put("RENTGG_PAYMENT_WEBHOOK_SECRET", "webhook-secret-xXx-0123456789abcdef");
        assertThatCode(() -> check(v, "prod").check()).doesNotThrowAnyException();
    }
}
