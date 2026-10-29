package com.rentgg.shared.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class Hmac {

    private Hmac() {}

    public static String sha256Hex(String secret, byte[] body) {
        return hex("HmacSHA256", secret, body);
    }

    public static String sha256Hex(String secret, String text) {
        return hex("HmacSHA256", secret, text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha512Hex(String secret, String text) {
        return hex("HmacSHA512", secret, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(String algorithm, String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time, case-insensitive comparison of two hex strings. */
    public static boolean hexEquals(String expectedHex, String providedHex) {
        if (expectedHex == null || providedHex == null || providedHex.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(expectedHex.toLowerCase().getBytes(StandardCharsets.US_ASCII),
                providedHex.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** Constant-time comparison of the expected signature with the provided hex string. */
    public static boolean verify(String secret, byte[] body, String providedHex) {
        if (providedHex == null || providedHex.isBlank()) {
            return false;
        }
        byte[] expected = sha256Hex(secret, body).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = providedHex.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided);
    }

    public static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
