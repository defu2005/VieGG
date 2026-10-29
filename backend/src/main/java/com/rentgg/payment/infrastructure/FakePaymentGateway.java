package com.rentgg.payment.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.config.RentGgProperties;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.shared.crypto.Hmac;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dev/test PSP (on when {@code rentgg.payment.fake-enabled}): the payment is completed with the dev "simulate"
 * endpoint or a webhook {"providerRef": "...", "status": "SUCCEEDED|FAILED", "amount": 290000} signed with
 * X-Signature = hex(HMAC-SHA256(webhookSecret, rawBody)).
 */
@Component
@ConditionalOnProperty(prefix = "rentgg.payment", name = "fake-enabled", havingValue = "true")
public class FakePaymentGateway implements PaymentGateway {

    private final RentGgProperties props;
    private final ObjectMapper mapper;

    public FakePaymentGateway(RentGgProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    @Override
    public String provider() {
        return "FAKE";
    }

    @Override
    public String displayName() {
        return "Giả lập (dev)";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public boolean sandbox() {
        return true;
    }

    @Override
    public Checkout createCheckout(CheckoutRequest req) {
        String url = props.payment().publicBaseUrl() + "/api/v1/dev/payments/" + req.intentId() + "/simulate-success";
        return new Checkout("fake_" + req.intentId(), url);
    }

    @Override
    public Verified verifyCallback(CallbackRequest req) {
        if (!Hmac.verify(props.payment().webhookSecret(), req.body(), req.header("X-Signature"))) {
            return Verified.INVALID;
        }
        try {
            JsonNode n = mapper.readTree(req.body());
            return Verified.of(new WebhookPayload(n.path("providerRef").asText(null), n.path("status").asText(null),
                    n.path("amount").asLong()));
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed webhook body");
        }
    }
}
