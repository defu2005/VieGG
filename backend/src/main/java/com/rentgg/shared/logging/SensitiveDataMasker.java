package com.rentgg.shared.logging;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the value of a sensitive key (password, token, secret, signature, authorization, key...) written as
 * {@code key=value}, {@code key: value}, {@code "key":"value"}, {@code "key" : "value"} or the JSON-escaped form.
 * A quoted value is masked through its closing quote, so spaces inside it do not leak. The key stays so the line
 * remains readable. Used by the text and the JSON log output (Dev Guide §2.1, System Design §16.1).
 *
 * <p>Limits: an unquoted value with spaces is masked only up to the first space; field names outside the list
 * above (for example {@code vnp_SecureHash}, {@code mac}) are not masked.
 */
public final class SensitiveDataMasker {

    public static final String MASK = "****";

    /**
     * Group 1: the key with its separator (any opening quote of the value is part of the value match).
     * Group 2: the value: an escaped quoted string, a double-quoted string, a single-quoted string, or an unquoted
     * token with an optional scheme word such as {@code Bearer}.
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "((?:(?:password|passwd|pwd|token|secret|signature|authorization|key)[\\w-]*)\\\\?\"?\\s*[:=]\\s*)"
                    + "(\\\\\"(?:[^\\\\\"]|\\\\(?!\"))*\\\\\"|\"[^\"]*\"|'[^']*'"
                    + "|(?:(?:bearer|basic|token)\\s+)?[^\\s,;&\"'}\\]\\\\]+)",
            Pattern.CASE_INSENSITIVE);

    private SensitiveDataMasker() {}

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher m = SENSITIVE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String value = m.group(2);
            String wrapped;
            if (value.startsWith("\\\"")) {
                wrapped = "\\\"" + MASK + "\\\"";
            } else if (value.startsWith("\"")) {
                wrapped = "\"" + MASK + "\"";
            } else if (value.startsWith("'")) {
                wrapped = "'" + MASK + "'";
            } else {
                wrapped = MASK;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + wrapped));
        }
        m.appendTail(out);
        return out.toString();
    }
}
