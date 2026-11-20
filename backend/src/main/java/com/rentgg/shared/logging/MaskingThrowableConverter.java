package com.rentgg.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** Replaces %wEx: masks exception messages and stack trace text. */
public class MaskingThrowableConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveDataMasker.mask(super.convert(event));
    }
}
