package com.rentgg.payment.application;

import com.rentgg.config.RentGgProperties;
import com.rentgg.shared.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Registry of the payment providers; only configured ones are offered to users. */
@Component
public class PaymentGateways {

    private final Map<String, PaymentGateway> byCode = new LinkedHashMap<>();
    private final String preferred;

    public PaymentGateways(List<PaymentGateway> gateways, RentGgProperties props) {
        gateways.forEach(g -> byCode.put(g.provider().toUpperCase(Locale.ROOT), g));
        String p = props.payment().provider();
        this.preferred = p == null ? "" : p.trim().toUpperCase(Locale.ROOT);
    }

    /** Configured providers, the default one first. */
    public List<PaymentGateway> available() {
        return byCode.values().stream().filter(PaymentGateway::isConfigured)
                .sorted((a, b) -> Boolean.compare(!a.provider().equals(preferred), !b.provider().equals(preferred)))
                .toList();
    }

    public Optional<PaymentGateway> find(String code) {
        return code == null ? Optional.empty()
                : Optional.ofNullable(byCode.get(code.trim().toUpperCase(Locale.ROOT))).filter(PaymentGateway::isConfigured);
    }

    /** {@code code == null} → the default provider. */
    public PaymentGateway require(String code) {
        if (code == null || code.isBlank()) {
            return available().stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PAYMENT_PROVIDER_UNAVAILABLE", "Chưa có cổng thanh toán nào được bật."));
        }
        return find(code).orElseThrow(() -> ApiException.badRequest("PAYMENT_PROVIDER_UNAVAILABLE",
                "Cổng thanh toán " + code + " không khả dụng."));
    }
}
