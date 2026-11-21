package com.rentgg.shared.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    static Stream<Arguments> masked() {
        return Stream.of(
                Arguments.of("password=hunter2", "password=****"),
                Arguments.of("PASSWORD: hunter2", "PASSWORD: ****"),
                Arguments.of("api_key=abc, next=1", "api_key=****, next=1"),
                Arguments.of("{\"token\":\"abc\"}", "{\"token\":\"****\"}"),
                Arguments.of("{\\\"token\\\":\\\"abc\\\"}", "{\\\"token\\\":\\\"****\\\"}"),
                Arguments.of("clientSecret=abc", "clientSecret=****"),
                Arguments.of("X-Signature: abc", "X-Signature: ****"),
                Arguments.of("Authorization: Bearer abc.def.ghi", "Authorization: ****"),
                Arguments.of("/x?token=abc&page=2", "/x?token=****&page=2"),
                Arguments.of("{\"password\":\"correct horse battery\"}", "{\"password\":\"****\"}"),
                Arguments.of("{\\\"password\\\":\\\"correct horse battery\\\"}", "{\\\"password\\\":\\\"****\\\"}"),
                Arguments.of("password='a b c'", "password='****'"),
                Arguments.of("\"token\" : \"a b\"", "\"token\" : \"****\""),
                Arguments.of("password=hunter2, next=1", "password=****, next=1"));
    }

    @ParameterizedTest
    @MethodSource("masked")
    void sensitiveValueIsReplaced(String input, String expected) {
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(expected);
    }

    static Stream<String> unchanged() {
        return Stream.of(
                "Webhook with invalid signature from provider PAYOS",
                "at com.rentgg.KeyStore.load(KeyStore.java:12)",
                "orderId=123 status=PENDING_PAYMENT",
                "");
    }

    @ParameterizedTest
    @MethodSource("unchanged")
    void nonSensitiveTextIsUnchanged(String input) {
        assertThat(SensitiveDataMasker.mask(input)).isEqualTo(input);
    }

    @Test
    void nullIsReturnedAsNull() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
    }
}
