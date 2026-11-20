package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.config.RentGgProperties.Payment;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.payment.application.PaymentGateways;
import com.rentgg.shared.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unconfigured path, without Spring: a provider with a blank key is not configured, so it is not offered,
 * cannot be found, and a top-up naming it is refused.
 */
class UnconfiguredProvidersTest {

    private static final PspHttp HTTP = new PspHttp(new ObjectMapper());

    @Test
    void aGatewayWithABlankKeyIsNotConfigured() {
        assertThat(new PayosGateway(PspTestProps.withPayos(new Payment.Payos("id", "", "ck", "https://x")), HTTP)
                .isConfigured()).isFalse();
        assertThat(new PayosGateway(PspTestProps.withPayos(new Payment.Payos("id", "ak", "", "https://x")), HTTP)
                .isConfigured()).isFalse();
        assertThat(new PayosGateway(PspTestProps.withPayos(new Payment.Payos("", "ak", "ck", "https://x")), HTTP)
                .isConfigured()).isFalse();
        assertThat(new PayosGateway(PspTestProps.withPayos(new Payment.Payos("id", "ak", "ck", "https://x")), HTTP)
                .isConfigured()).isTrue();
    }

    @Test
    void anUnconfiguredProviderIsHiddenFromTheRegistry() {
        var payos = new PayosGateway(PspTestProps.withPayos(new Payment.Payos("", "", "", "https://x")), HTTP);
        var registry = new PaymentGateways(List.of(payos), PspTestProps.withPayos(new Payment.Payos("", "", "", "https://x")));

        assertThat(registry.available()).isEmpty();
        assertThat(registry.find("PAYOS")).isEqualTo(Optional.empty());
        assertThatThrownBy(() -> registry.require("PAYOS"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("PAYMENT_PROVIDER_UNAVAILABLE"));
    }
}
