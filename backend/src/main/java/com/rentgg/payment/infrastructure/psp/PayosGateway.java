package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.rentgg.config.RentGgProperties;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.shared.crypto.Hmac;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * PayOS (VietQR bank transfer). Free: create a payment channel at https://my.payos.vn (client id, API key, checksum
 * key). Webhook URL is set in the PayOS dashboard: {@code <public>/api/v1/payments/webhook/payos}. The return URL is
 * not signed, so the redirect asks the PayOS API for the payment status.
 */
@Component
public class PayosGateway implements PaymentGateway {

    private final RentGgProperties.Payment.Payos cfg;
    private final PspHttp http;

    public PayosGateway(RentGgProperties props, PspHttp http) {
        this.cfg = props.payment().payos();
        this.http = http;
    }

    @Override
    public String provider() {
        return "PAYOS";
    }

    @Override
    public String displayName() {
        return "Chuyển khoản VietQR (PayOS)";
    }

    @Override
    public boolean isConfigured() {
        return cfg != null && !PspHttp.blank(cfg.clientId()) && !PspHttp.blank(cfg.apiKey())
                && !PspHttp.blank(cfg.checksumKey());
    }

    /** PayOS has no separate sandbox: transfers are real, so keep amounts small when testing. */
    @Override
    public boolean sandbox() {
        return false;
    }

    @Override
    public Checkout createCheckout(CheckoutRequest req) {
        // orderCode must be a positive integer ≤ 9007199254740991: millis × 1000 + random suffix.
        long orderCode = System.currentTimeMillis() * 1000 + ThreadLocalRandom.current().nextInt(1000);
        String description = "Nap vi"; // ≤ 9 chars for banks not linked to PayOS
        String raw = "amount=" + req.amount() + "&cancelUrl=" + req.returnUrl() + "&description=" + description
                + "&orderCode=" + orderCode + "&returnUrl=" + req.returnUrl();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", orderCode);
        body.put("amount", req.amount());
        body.put("description", description);
        body.put("cancelUrl", req.returnUrl());
        body.put("returnUrl", req.returnUrl());
        if (req.expiresAt() != null) {
            body.put("expiredAt", req.expiresAt().getEpochSecond());
        }
        body.put("signature", Hmac.sha256Hex(cfg.checksumKey(), raw));
        JsonNode res = http.postJson(cfg.endpoint() + "/v2/payment-requests", body, auth());
        String url = res.path("data").path("checkoutUrl").asText("");
        if (!"00".equals(res.path("code").asText()) || url.isEmpty()) {
            throw new ProviderException("PayOS từ chối tạo giao dịch: " + res.path("desc").asText("không rõ lý do"));
        }
        return new Checkout(String.valueOf(orderCode), url);
    }

    /** Webhook: {@code {code, desc, success, data: {...}, signature}}; signature over the sorted data fields. */
    @Override
    public Verified verifyCallback(CallbackRequest req) {
        JsonNode n = http.read(req.body());
        JsonNode data = n.path("data");
        String signature = n.path("signature").asText(null);
        if (!data.isObject() || signature == null) {
            throw new IllegalArgumentException("Missing PayOS fields");
        }
        if (!Hmac.hexEquals(Hmac.sha256Hex(cfg.checksumKey(), signedData(data)), signature)) {
            return Verified.INVALID;
        }
        if (!data.path("orderCode").canConvertToLong() || !data.path("amount").canConvertToLong()) {
            throw new IllegalArgumentException("Missing PayOS data fields");
        }
        boolean ok = "00".equals(n.path("code").asText()) && "00".equals(data.path("code").asText("00"));
        return Verified.of(new WebhookPayload(data.path("orderCode").asText(), ok ? "SUCCEEDED" : "FAILED",
                data.path("amount").asLong()));
    }

    /** Redirect carries {@code orderCode}; the status comes from the PayOS API (authenticated). */
    @Override
    public Verified verifyReturn(CallbackRequest req) {
        String orderCode = req.param("orderCode");
        if (orderCode == null || !orderCode.matches("\\d{1,16}")) {
            throw new IllegalArgumentException("Missing orderCode");
        }
        JsonNode res = http.get(cfg.endpoint() + "/v2/payment-requests/" + orderCode, auth());
        if (!"00".equals(res.path("code").asText())) {
            return Verified.INVALID;
        }
        JsonNode d = res.path("data");
        String status = switch (d.path("status").asText("")) {
            case "PAID" -> "SUCCEEDED";
            case "CANCELLED", "EXPIRED", "FAILED" -> "FAILED";
            default -> "PENDING";
        };
        return Verified.of(new WebhookPayload(orderCode, status, d.path("amount").asLong()));
    }

    @Override
    public Ack ack(Outcome outcome) {
        return switch (outcome) {
            case INVALID_SIGNATURE, MALFORMED -> new Ack(400, Map.of("success", false));
            default -> new Ack(200, Map.of("success", true));
        };
    }

    private Map<String, String> auth() {
        return Map.of("x-client-id", cfg.clientId(), "x-api-key", cfg.apiKey());
    }

    /** {@code key=value} sorted by key, joined by '&'; null → "", objects/arrays as JSON. */
    static String signedData(JsonNode data) {
        List<String> keys = new ArrayList<>();
        data.fieldNames().forEachRemaining(keys::add);
        keys.sort(null);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            JsonNode v = data.get(k);
            String s = v == null || v.isNull() ? "" : v.isContainerNode() ? v.toString() : v.asText();
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(k).append('=').append(s);
        }
        return sb.toString();
    }
}
