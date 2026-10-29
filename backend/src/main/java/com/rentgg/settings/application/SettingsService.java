package com.rentgg.settings.application;

import com.rentgg.audit.application.AuditService;
import com.rentgg.settings.domain.Setting;
import com.rentgg.settings.domain.SettingType;
import com.rentgg.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Runtime platform settings ({@link Setting}). Values are read from the database on every call (the table is tiny)
 * so every backend sharing the database sees an admin's change at once.
 */
@Service
public class SettingsService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public SettingsService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public record SettingView(String key, String group, String groupLabel, SettingType type, String label,
                              String description, String value, String defaultValue, boolean customized,
                              boolean publicValue, Long min, Long max, List<String> choices, Instant updatedAt,
                              UUID updatedBy) {}

    private record Row(String value, Instant updatedAt, UUID updatedBy) {}

    // ================================================================= reads

    public String get(Setting s) {
        List<String> v = jdbc.queryForList("select value from app_settings where key = ?", String.class, s.key());
        return v.isEmpty() ? s.defaultValue() : v.get(0);
    }

    public long getLong(Setting s) {
        try {
            return Long.parseLong(get(s));
        } catch (NumberFormatException e) {
            return Long.parseLong(s.defaultValue());
        }
    }

    public boolean getBoolean(Setting s) {
        return Boolean.parseBoolean(get(s));
    }

    @Transactional(readOnly = true)
    public List<SettingView> list() {
        Map<String, Row> rows = rows();
        return Arrays.stream(Setting.values()).map(s -> view(s, rows.get(s.key()))).toList();
    }

    /** Values of the public settings, by key (defaults included). */
    @Transactional(readOnly = true)
    public Map<String, String> publicValues() {
        Map<String, Row> rows = rows();
        Map<String, String> out = new LinkedHashMap<>();
        for (Setting s : Setting.values()) {
            if (s.publicValue()) {
                Row r = rows.get(s.key());
                out.put(s.key(), r == null ? s.defaultValue() : r.value());
            }
        }
        return out;
    }

    // ================================================================= writes

    /**
     * Sets several values at once (all or nothing). A {@code null} value resets the setting to its default.
     * Validation errors list every bad key in {@code details.fields}.
     */
    @Transactional
    public List<SettingView> update(UUID actorId, Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            throw ApiException.badRequest("VALIDATION_ERROR", "Không có cấu hình nào để lưu.");
        }
        jdbc.execute("lock table app_settings in share row exclusive mode");
        Map<String, Row> rows = rows();
        Map<String, String> current = new HashMap<>();
        for (Setting s : Setting.values()) {
            Row r = rows.get(s.key());
            current.put(s.key(), r == null ? s.defaultValue() : r.value());
        }

        Map<Setting, String> normalized = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        changes.forEach((key, raw) -> {
            Setting s = Setting.byKey(key).orElse(null);
            if (s == null) {
                errors.put(key, "Cấu hình không tồn tại.");
                return;
            }
            String value = raw == null ? s.defaultValue() : normalize(s, raw, errors);
            if (value != null) {
                normalized.put(s, value);
                current.put(key, value);
            }
        });
        crossCheck(current, errors);
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SETTINGS",
                    "Cấu hình không hợp lệ: " + String.join("; ", errors.values()), Map.of("fields", errors));
        }

        Instant now = clock.instant();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        for (var e : normalized.entrySet()) {
            Setting s = e.getKey();
            Row old = rows.get(s.key());
            String oldValue = old == null ? s.defaultValue() : old.value();
            if (Objects.equals(oldValue, e.getValue())) {
                continue;
            }
            if (e.getValue().equals(s.defaultValue())) {
                jdbc.update("delete from app_settings where key = ?", s.key());
            } else {
                jdbc.update("""
                        insert into app_settings (key, value, updated_by, updated_at) values (?, ?, ?, ?)
                        on conflict (key) do update set value = excluded.value, updated_by = excluded.updated_by,
                                                        updated_at = excluded.updated_at""",
                        s.key(), e.getValue(), actorId, Timestamp.from(now));
            }
            before.put(s.key(), oldValue);
            after.put(s.key(), e.getValue());
        }
        if (!after.isEmpty()) {
            audit.record(actorId, "SETTINGS_UPDATED", "AppSettings", null, before, after, null);
        }
        return list();
    }

    // ================================================================= validation

    private static String normalize(Setting s, String raw, Map<String, String> errors) {
        String v = s.type() == SettingType.LONG_TEXT ? raw.strip().replace("\r\n", "\n") : raw.strip();
        String label = s.label();
        switch (s.type()) {
            case MONEY, INTEGER -> {
                long n;
                try {
                    n = Long.parseLong(v.replace(".", "").replace(",", "").replace(" ", ""));
                } catch (NumberFormatException e) {
                    errors.put(s.key(), label + " phải là số nguyên.");
                    return null;
                }
                if (s.min() != null && n < s.min() || s.max() != null && n > s.max()) {
                    errors.put(s.key(), label + " phải trong khoảng " + s.min() + " – " + s.max() + ".");
                    return null;
                }
                return Long.toString(n);
            }
            case BOOLEAN -> {
                if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    errors.put(s.key(), label + " phải là true hoặc false.");
                    return null;
                }
                return v.toLowerCase();
            }
            case TEXT -> {
                if (v.contains("\n")) {
                    errors.put(s.key(), label + " chỉ được một dòng.");
                    return null;
                }
                if (v.length() > SettingType.TEXT_MAX) {
                    errors.put(s.key(), label + " tối đa " + SettingType.TEXT_MAX + " ký tự.");
                    return null;
                }
                return v;
            }
            case LONG_TEXT -> {
                if (v.length() > SettingType.LONG_TEXT_MAX) {
                    errors.put(s.key(), label + " tối đa " + SettingType.LONG_TEXT_MAX + " ký tự.");
                    return null;
                }
                return v;
            }
            case URL -> {
                boolean ok = v.isEmpty() || v.matches("/[^\\s]*") && !v.startsWith("//")
                        || v.matches("https?://[^\\s/$.?#][^\\s]*");
                if (!ok || v.length() > 500) {
                    errors.put(s.key(), label + " phải bắt đầu bằng / hoặc http(s)://.");
                    return null;
                }
                return v;
            }
            case CHOICE -> {
                if (!s.choices().contains(v)) {
                    errors.put(s.key(), label + " phải là một trong " + s.choices() + ".");
                    return null;
                }
                return v;
            }
        }
        return v;
    }

    private static void crossCheck(Map<String, String> values, Map<String, String> errors) {
        long min = Long.parseLong(values.get(Setting.WITHDRAWAL_MIN_AMOUNT.key()));
        long max = Long.parseLong(values.get(Setting.WITHDRAWAL_MAX_AMOUNT.key()));
        if (max > 0 && max < min && !errors.containsKey(Setting.WITHDRAWAL_MAX_AMOUNT.key())) {
            errors.put(Setting.WITHDRAWAL_MAX_AMOUNT.key(), "Số tiền rút tối đa phải ≥ số tiền tối thiểu (hoặc 0).");
        }
        if (Boolean.parseBoolean(values.get(Setting.BANNER_ENABLED.key()))
                && values.get(Setting.BANNER_MESSAGE.key()).isBlank()
                && !errors.containsKey(Setting.BANNER_MESSAGE.key())) {
            errors.put(Setting.BANNER_MESSAGE.key(), "Cần nhập nội dung banner trước khi bật banner.");
        }
    }

    private Map<String, Row> rows() {
        Map<String, Row> out = new HashMap<>();
        jdbc.query("select key, value, updated_at, updated_by from app_settings", rs -> {
            out.put(rs.getString("key"), new Row(rs.getString("value"), rs.getTimestamp("updated_at").toInstant(),
                    rs.getObject("updated_by", UUID.class)));
        });
        return out;
    }

    private static SettingView view(Setting s, Row r) {
        return new SettingView(s.key(), s.group().name(), s.group().label(), s.type(), s.label(), s.description(),
                r == null ? s.defaultValue() : r.value(), s.defaultValue(), r != null, s.publicValue(), s.min(),
                s.max(), s.choices().isEmpty() ? null : s.choices(), r == null ? null : r.updatedAt(),
                r == null ? null : r.updatedBy());
    }
}
