package com.rentgg.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC for the PSP contract tests, written with javax.crypto only. Tests must never reuse the adapter's helper:
 * the signed string is spelled out in the test, so a mismatch in the adapter is caught instead of agreed with.
 */
public final class TestHmac {

    private TestHmac() {}

    public static String sha256Hex(String key, String text) {
        return hex("HmacSHA256", key, text);
    }

    public static String sha512Hex(String key, String text) {
        return hex("HmacSHA512", key, text);
    }

    private static String hex(String algorithm, String key, String text) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
