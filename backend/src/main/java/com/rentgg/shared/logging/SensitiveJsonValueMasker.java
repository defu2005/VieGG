package com.rentgg.shared.logging;

import com.fasterxml.jackson.core.JsonStreamContext;
import net.logstash.logback.mask.ValueMasker;

/**
 * Masks string values in JSON log output before they are encoded. Returns {@code null} when nothing changed,
 * so the value is written as it is.
 */
public class SensitiveJsonValueMasker implements ValueMasker {

    @Override
    public Object mask(JsonStreamContext context, Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String masked = SensitiveDataMasker.mask(text);
        return masked.equals(text) ? null : masked;
    }
}
