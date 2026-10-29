package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.rentgg.config.RentGgProperties;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.shared.crypto.Hmac;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MoMo e-wallet (API v2, captureWallet). Sandbox: https://test-payment.momo.vn with the public test partner from the
 * MoMo developer docs; pay in the MoMo test app. Result: signed (HMAC-SHA256) IPN POST and signed redirect params.
 */
@Component
public class MomoGateway implements PaymentGateway {

    private static final String[] RESULT_FIELDS = {"amount", "extraData", "message", "orderId", "orderInfo",
            "orderType", "partnerCode", "payType", "requestId", "responseTime", "resultCode", "transId"};

    private final RentGgProperties.Payment.Momo cfg;
    private final PspHttp http;

    public MomoGateway(RentGgProperties props, PspHttp http) {
        this.cfg = props.payment().momo();
        this.http = http;
    }

    @Override
    public String provider() {
        return "MOMO";
    }

    @Override
    public String displayName() {
        return "Ví MoMo";
    }

    @Override
    public boolean isConfigured() {
        return cfg != null && !PspHttp.blank(cfg.partnerCode()) && !PspHttp.blank(cfg.accessKey())
                && !PspHttp.blank(cfg.secretKey());
    }

    @Override
    public boolean sandbox() {
        return cfg != null && cfg.endpoint() != null && cfg.endpoint().contains("test-");
    }

    @Override
    public Checkout createCheckout(CheckoutRequest req) {
        String orderId = "RGG" + PspHttp.compact(req.intentId());
        String orderInfo = "Nap vi VieGG";
        String requestType = "captureWallet";
        String raw = "accessKey=" + cfg.accessKey() + "&amount=" + req.amount() + "&extraData=" + "&ipnUrl=" + req.notifyUrl()
                + "&orderId=" + orderId + "&orderInfo=" + orderInfo + "&partnerCode=" + cfg.partnerCode()
                + "&redirectUrl=" + req.returnUrl() + "&requestId=" + orderId + "&requestType=" + requestType;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", cfg.partnerCode());
        body.put("partnerName", "VieGG");
        body.put("storeId", "VieGG");
        body.put("requestId", orderId);
        body.put("amount", req.amount());
        body.put("orderId", orderId);
        body.put("orderInfo", orderInfo);
        body.put("redirectUrl", req.returnUrl());
        body.put("ipnUrl", req.notifyUrl());
        body.put("lang", "vi");
        body.put("requestType", requestType);
        body.put("autoCapture", true);
        body.put("extraData", "");
        body.put("signature", Hmac.sha256Hex(cfg.secretKey(), raw));
        JsonNode res = http.postJson(cfg.endpoint() + "/v2/gateway/api/create", body, Map.of());
        if (res.path("resultCode").asInt(-1) != 0 || res.path("payUrl").asText("").isEmpty()) {
            throw new ProviderException("MoMo từ chối tạo giao dịch: " + res.path("message").asText("không rõ lý do"));
        }
        return new Checkout(orderId, res.path("payUrl").asText());
    }

    @Override
    public Verified verifyCallback(CallbackRequest req) {
        JsonNode n = http.read(req.body());
        Map<String, String> f = new HashMap<>();
        for (String k : RESULT_FIELDS) {
            f.put(k, n.path(k).isMissingNode() || n.path(k).isNull() ? "" : n.path(k).asText());
        }
        return verify(f, n.path("signature").asText(null));
    }

    @Override
    public Verified verifyReturn(CallbackRequest req) {
        Map<String, String> f = new HashMap<>();
        for (String k : RESULT_FIELDS) {
            f.put(k, req.params().getOrDefault(k, ""));
        }
        return verify(f, req.param("signature"));
    }

    private Verified verify(Map<String, String> f, String signature) {
        if (f.get("orderId").isEmpty() || f.get("resultCode").isEmpty() || signature == null) {
            throw new IllegalArgumentException("Missing MoMo fields");
        }
        StringBuilder raw = new StringBuilder("accessKey=").append(cfg.accessKey());
        for (String k : RESULT_FIELDS) {
            raw.append('&').append(k).append('=').append(f.get(k));
        }
        if (!Hmac.hexEquals(Hmac.sha256Hex(cfg.secretKey(), raw.toString()), signature)) {
            return Verified.INVALID;
        }
        String code = f.get("resultCode");
        // 0 = paid; 9000 = authorised, capture follows (autoCapture); 1000 = waiting for the payer.
        String status = "0".equals(code) ? "SUCCEEDED" : ("9000".equals(code) || "1000".equals(code)) ? "PENDING" : "FAILED";
        long amount;
        try {
            amount = Long.parseLong(f.get("amount"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad MoMo amount");
        }
        return Verified.of(new WebhookPayload(f.get("orderId"), status, amount));
    }

    /** MoMo expects 204 for a processed IPN. */
    @Override
    public Ack ack(Outcome outcome) {
        return switch (outcome) {
            case INVALID_SIGNATURE, MALFORMED -> new Ack(400, Map.of("message", outcome.name()));
            default -> new Ack(204, null);
        };
    }
}
