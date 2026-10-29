package com.rentgg.catalog.domain;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Per-game account identity rule. {@code label}/{@code example} are what owners see on the form
 * (e.g. "UID", "800123456"); {@code perServer} = the same id may exist once per server region.
 */
public record AccountKeyRule(@NotNull AccountKeyType type,
                             @Size(max = 60) String label,
                             @Size(max = 120) String example,
                             boolean perServer) {

    public static final AccountKeyRule LOGIN = new AccountKeyRule(AccountKeyType.LOGIN, null, null, false);

    private static final Pattern HEX32 = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,30}");
    /** Zero-width and other invisible format characters an owner could use to make a name look different. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cf}\\p{Cc}]");

    public AccountKeyRule {
        label = blankToNull(label);
        example = blankToNull(example);
        if (type == AccountKeyType.LOGIN) {
            perServer = false;
        }
    }

    public boolean usesLogin() {
        return type == AccountKeyType.LOGIN;
    }

    /** Label shown to owners, with a default per type. */
    public String displayLabel() {
        if (label != null) return label;
        return switch (type) {
            case LOGIN -> "Tên đăng nhập";
            case UUID -> "UUID nhân vật";
            case NUMERIC_ID -> "UID";
            case NAME -> "Tên trong game";
        };
    }

    /** True when switching from this rule to {@code next} changes how existing keys were computed. */
    public boolean changesIdentity(AccountKeyRule next) {
        return type != next.type || perServer != next.perServer;
    }

    /**
     * Normalizes a raw identifier so look-alike variants collide: case, spaces, invisible characters, full-width
     * digits, leading zeros, UUID with or without dashes. Returns null if the value is not valid for this type.
     */
    public String normalize(String raw) {
        if (raw == null) return null;
        String s = INVISIBLE.matcher(Normalizer.normalize(raw, Normalizer.Form.NFKC)).replaceAll("").trim();
        if (s.isEmpty()) return null;
        return switch (type) {
            case LOGIN -> null;
            case UUID -> {
                String hex = s.replace("-", "").replace("{", "").replace("}", "").toLowerCase(Locale.ROOT);
                if (!HEX32.matcher(hex).matches()) yield null;
                yield hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
                        + hex.substring(16, 20) + "-" + hex.substring(20);
            }
            case NUMERIC_ID -> {
                String digits = s.replaceAll("[\\s.\\-]", "");
                if (!DIGITS.matcher(digits).matches()) yield null;
                String stripped = digits.replaceFirst("^0+(?=.)", "");
                yield stripped;
            }
            case NAME -> {
                String name = s.replaceAll("\\s+", " ").replaceAll("\\s*#\\s*", "#").toLowerCase(Locale.ROOT);
                yield name.length() > 100 ? null : name;
            }
        };
    }

    /** Scope for the unique index: the normalized server region when ids are per server, '' otherwise. */
    public String scope(String serverRegion) {
        if (!perServer) return "";
        return serverRegion == null ? "" : serverRegion.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public String invalidMessage() {
        String l = displayLabel();
        return switch (type) {
            case LOGIN -> "";
            case UUID -> l + " phải là UUID hợp lệ (32 ký tự hex, có hoặc không có dấu gạch).";
            case NUMERIC_ID -> l + " chỉ gồm chữ số (tối đa 30 số).";
            case NAME -> l + " bắt buộc, tối đa 100 ký tự.";
        };
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
