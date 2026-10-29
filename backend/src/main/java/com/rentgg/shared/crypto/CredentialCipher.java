package com.rentgg.shared.crypto;

import com.rentgg.config.RentGgProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * AES-256-GCM with a random 12-byte IV per encryption, 128-bit tag and
 * AAD = game_account_id || version (System Design §12.3). Keys come from the environment, never the DB.
 */
@Component
public class CredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKey> keys;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    @org.springframework.beans.factory.annotation.Autowired
    public CredentialCipher(RentGgProperties props) {
        this(props.credentials().keys(), props.credentials().activeKeyId());
    }

    public CredentialCipher(String keySpec, String activeKeyId) {
        if (keySpec == null || keySpec.isBlank()) {
            throw new IllegalStateException("RENTGG_CREDENTIAL_KEYS is required");
        }
        Map<String, SecretKey> parsed = new HashMap<>();
        for (String entry : keySpec.split(",")) {
            String e = entry.trim();
            int idx = e.indexOf(':');
            if (idx <= 0) {
                throw new IllegalStateException("Invalid RENTGG_CREDENTIAL_KEYS entry (expected keyId:base64)");
            }
            byte[] raw = Base64.getDecoder().decode(e.substring(idx + 1));
            if (raw.length != 32) {
                throw new IllegalStateException("Credential key " + e.substring(0, idx) + " must be 32 bytes (AES-256)");
            }
            parsed.put(e.substring(0, idx), new SecretKeySpec(raw, "AES"));
        }
        if (activeKeyId == null || !parsed.containsKey(activeKeyId)) {
            throw new IllegalStateException("RENTGG_CREDENTIAL_ACTIVE_KEY_ID must reference a configured key");
        }
        this.keys = Map.copyOf(parsed);
        this.activeKeyId = activeKeyId;
    }

    public Encrypted encrypt(UUID gameAccountId, int version, byte[] plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(gameAccountId, version));
            return new Encrypted(cipher.doFinal(plaintext), iv, activeKeyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Credential encryption failed", e);
        }
    }

    public byte[] decrypt(UUID gameAccountId, int version, byte[] ciphertext, byte[] iv, String keyId) {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new IllegalStateException("Unknown credential key id " + keyId);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(gameAccountId, version));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Credential decryption failed (tampered data or wrong key)", e);
        }
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * Deterministic, keyed fingerprint of a game login (username trimmed, case-insensitive), used to detect the same
     * account being added twice. The HMAC key is derived from the active credential key, so after rotating
     * RENTGG_CREDENTIAL_ACTIVE_KEY_ID set game_accounts.login_fingerprint to null and restart to recompute it.
     */
    public String loginFingerprint(UUID gameId, String username) {
        try {
            Mac kdf = Mac.getInstance("HmacSHA256");
            kdf.init(keys.get(activeKeyId));
            byte[] fpKey = kdf.doFinal("rentgg:login-fingerprint:v1".getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(fpKey, "HmacSHA256"));
            String normalized = gameId + "\n" + username.trim().toLowerCase(Locale.ROOT);
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Login fingerprint failed", e);
        }
    }

    private static byte[] aad(UUID gameAccountId, int version) {
        byte[] id = gameAccountId.toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(id.length + 4).put(id).putInt(version).array();
    }

    public record Encrypted(byte[] ciphertext, byte[] iv, String keyId) {}
}
