package com.rentgg.identity.application;

import com.rentgg.shared.crypto.CredentialCipher;
import com.rentgg.shared.error.ApiException;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * TOTP (RFC 6238) second factor for staff: 6 digits, 30 seconds, SHA-1. Accepts the current step and one step on
 * either side. A step is accepted at most once. Five wrong codes lock the account for 15 minutes.
 */
@Service
public class TotpService {

    /** Outcome of a code check. */
    public enum Verdict { OK, INVALID, LOCKED, NOT_ENROLLED }

    static final int PERIOD_SECONDS = 30;
    static final int MAX_FAILURES = 5;
    static final Duration LOCK_FOR = Duration.ofMinutes(15);
    /** Version tag for the encrypted secret (CredentialCipher binds it with the user id). */
    private static final int SECRET_VERSION = 1;

    private final JdbcTemplate jdbc;
    private final CredentialCipher cipher;
    private final DefaultCodeGenerator codes = new DefaultCodeGenerator();
    private final DefaultSecretGenerator secrets = new DefaultSecretGenerator();

    public TotpService(JdbcTemplate jdbc, CredentialCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** {@code secret} is Base32 and is shown once, during enrolment. */
    public record Enrollment(String secret, String otpauthUri) {}

    /** Creates or replaces a pending (not yet confirmed) secret. Refused once the user is enrolled. */
    public Enrollment startEnrollment(UUID userId, String email) {
        if (isEnrolled(userId)) {
            throw ApiException.conflict("MFA_ALREADY_ENROLLED", "Tài khoản này đã bật xác thực hai bước.");
        }
        String secret = secrets.generate();
        CredentialCipher.Encrypted enc = cipher.encrypt(userId, SECRET_VERSION, secret.getBytes(StandardCharsets.UTF_8));
        jdbc.update("delete from user_totp where user_id = ? and confirmed_at is null", userId);
        jdbc.update("""
                insert into user_totp (user_id, secret_key_id, secret_iv, secret_ct)
                values (?, ?, ?, ?)""", userId, enc.keyId(), enc.iv(), enc.ciphertext());
        String label = URLEncoder.encode("RentGG:" + email, StandardCharsets.UTF_8);
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=RentGG&digits=6&period=30";
        return new Enrollment(secret, uri);
    }

    /** Confirms the pending secret with a code from the authenticator app. */
    public void confirmEnrollment(UUID userId, String code, Instant now) {
        Map<String, Object> row = findRow(userId).orElseThrow(() ->
                ApiException.notFound("Chưa có yêu cầu bật xác thực hai bước"));
        if (row.get("confirmed_at") != null) {
            throw ApiException.conflict("MFA_ALREADY_ENROLLED", "Tài khoản này đã bật xác thực hai bước.");
        }
        String secret = decryptSecret(userId, row);
        long step = now.getEpochSecond() / PERIOD_SECONDS;
        Long matched = matchingStep(secret, code, step);
        if (matched == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MFA_INVALID", "Mã xác thực không đúng.");
        }
        jdbc.update("update user_totp set confirmed_at = ?, last_used_step = ? where user_id = ?",
                Timestamp.from(now), matched, userId);
    }

    public boolean isEnrolled(UUID userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from user_totp where user_id = ? and confirmed_at is not null", Integer.class, userId);
        return n != null && n > 0;
    }

    /**
     * Checks a login code. A wrong or reused code counts as a failure; the fifth failure sets {@code locked_until}.
     * Runs inside the caller's transaction, which must not roll back on ApiException (see AuthService.login).
     */
    public Verdict verify(UUID userId, String code, Instant now) {
        Map<String, Object> row = findRow(userId).filter(r -> r.get("confirmed_at") != null).orElse(null);
        if (row == null) {
            return Verdict.NOT_ENROLLED;
        }
        Timestamp lockedUntil = (Timestamp) row.get("locked_until");
        if (lockedUntil != null && lockedUntil.toInstant().isAfter(now)) {
            return Verdict.LOCKED;
        }
        if (code == null || code.isBlank()) {
            return recordFailure(userId, now);
        }
        String secret = decryptSecret(userId, row);
        Long matched = matchingStep(secret, code.trim(), now.getEpochSecond() / PERIOD_SECONDS);
        if (matched != null) {
            // Conditional update: two concurrent logins with the same code cannot both succeed.
            int updated = jdbc.update("""
                    update user_totp set last_used_step = ?, failed_attempts = 0, locked_until = null
                    where user_id = ? and (last_used_step is null or last_used_step < ?)""",
                    matched, userId, matched);
            if (updated == 1) {
                return Verdict.OK;
            }
        }
        return recordFailure(userId, now);
    }

    /** The step whose code equals {@code code}, among step-1, step and step+1; {@code null} if none matches. */
    private Long matchingStep(String secret, String code, long step) {
        byte[] given = code.getBytes(StandardCharsets.UTF_8);
        for (long s = step - 1; s <= step + 1; s++) {
            try {
                String expected = codes.generate(secret, s);
                if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given)) {
                    return s;
                }
            } catch (dev.samstevens.totp.exceptions.CodeGenerationException e) {
                throw new IllegalStateException("TOTP code generation failed", e);
            }
        }
        return null;
    }

    private Verdict recordFailure(UUID userId, Instant now) {
        // One statement: the counter and the lock are set from the same read of failed_attempts.
        jdbc.update("""
                update user_totp set
                  failed_attempts = case when failed_attempts + 1 >= ? then 0 else failed_attempts + 1 end,
                  locked_until = case when failed_attempts + 1 >= ? then ? else locked_until end
                where user_id = ?""",
                MAX_FAILURES, MAX_FAILURES, Timestamp.from(now.plus(LOCK_FOR)), userId);
        Timestamp lockedUntil = (Timestamp) findRow(userId).map(r -> r.get("locked_until")).orElse(null);
        return lockedUntil != null && lockedUntil.toInstant().isAfter(now) ? Verdict.LOCKED : Verdict.INVALID;
    }

    private java.util.Optional<Map<String, Object>> findRow(UUID userId) {
        return jdbc.queryForList("select * from user_totp where user_id = ?", userId).stream().findFirst()
                .map(r -> r);
    }

    private String decryptSecret(UUID userId, Map<String, Object> row) {
        byte[] plain = cipher.decrypt(userId, SECRET_VERSION, (byte[]) row.get("secret_ct"), (byte[]) row.get("secret_iv"),
                (String) row.get("secret_key_id"));
        return new String(plain, StandardCharsets.UTF_8);
    }
}
