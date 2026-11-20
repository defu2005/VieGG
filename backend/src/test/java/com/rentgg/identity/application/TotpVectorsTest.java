package com.rentgg.identity.application;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** RFC 6238 Appendix B (SHA-1, ASCII secret "12345678901234567890"), 6-digit codes: the last six digits of the RFC values. */
class TotpVectorsTest {

    /** Base32 of "12345678901234567890". */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfcVectorsMatchWithZeroPadding() throws Exception {
        DefaultCodeGenerator gen = new DefaultCodeGenerator();
        assertThat(gen.generate(RFC_SECRET, 59L / 30)).isEqualTo("287082");       // T=59 -> 94287082
        assertThat(gen.generate(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804"); // T=1111111109 -> 07081804
    }
}
