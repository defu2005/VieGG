package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.rentgg.config.RentGgProperties;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.shared.crypto.Hmac;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ZaloPay (API v2). Sandbox: https://sb-openapi.zalopay.vn with the public test app from the ZaloPay docs; pay in
 * the ZaloPay sandbox app or with test cards. Order is signed with key1; callback ({@code data} + {@code mac}) and
 * redirect ({@code checksum}) are signed with key2 (HMAC-SHA256).
 */
@Component
public class ZalopayGateway implements PaymentGateway {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final RentGgProperties.Payment.Zalopay cfg;
    private final PspHttp http;

    public ZalopayGateway(RentGgProperties props, PspHttp http) {
        this.cfg = props.payment().zalopay();
        this.http = http;
    }

    @Override
    public String provider() {
        return "ZALOPAY";
    }

    @Override
    public String displayName() {
        return "ZaloPay (ví, ATM, thẻ)";
    }

    @Override
    public boolean isConfigured() {
        return cfg != null && !PspHttp.blank(cfg.appId()) && !PspHttp.blank(cfg.key1()) && !PspHttp.blank(cfg.key2());
    }

    @Override
    public boolean sandbox() {
        return cfg != null && cfg.endpoint() != null && cfg.endpoint().contains("sb-");
    }

    @Override
    public Checkout createCheckout(CheckoutRequest req) {
        String transId = DAY.format(ZonedDateTime.now(VN)) + "_" + PspHttp.compact(req.intentId());
        String appUser = "VieGG";
        String appTime = String.valueOf(System.currentTimeMillis());
        String embed = http.write(Map.of("redirecturl", req.returnUrl()));
        String item = "[]";
        String amount = String.valueOf(req.amount());
        String mac = Hmac.sha256Hex(cfg.key1(),
                String.join("|", cfg.appId(), transId, appUser, amount, appTime, embed, item));
        Map<String, String> form = new LinkedHashMap<>();
        form.put("app_id", cfg.appId());
        form.put("app_trans_id", transId);
        form.put("app_user", appUser);
        form.put("app_time", appTime);
        form.put("amount", amount);
        form.put("item", item);
        form.put("embed_data", embed);
        form.put("description", "VieGG - Nap vi #" + transId.substring(7, 15));
        form.put("bank_code", "");
        form.put("callback_url", req.notifyUrl());
        form.put("mac", mac);
        JsonNode res = http.postForm(cfg.endpoint() + "/v2/create", form);
        if (res.path("return_code").asInt(-1) != 1 || res.path("order_url").asText("").isEmpty()) {
            throw new ProviderException("ZaloPay từ chối tạo giao dịch: "
                    + res.path("sub_return_message").asText(res.path("return_message").asText("không rõ lý do")));
        }
        return new Checkout(transId, res.path("order_url").asText());
    }

    /** Callback body: {@code {"data": "<json string>", "mac": "...", "type": 1}}; only sent for paid orders. */
    @Override
    public Verified verifyCallback(CallbackRequest req) {
        JsonNode n = http.read(req.body());
        String data = n.path("data").asText(null);
        String mac = n.path("mac").asText(null);
        if (data == null || mac == null) {
            throw new IllegalArgumentException("Missing ZaloPay fields");
        }
        if (!Hmac.hexEquals(Hmac.sha256Hex(cfg.key2(), data), mac)) {
            return Verified.INVALID;
        }
        JsonNode d = http.read(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String ref = d.path("app_trans_id").asText(null);
        if (ref == null || !d.path("amount").canConvertToLong()) {
            throw new IllegalArgumentException("Missing ZaloPay data fields");
        }
        return Verified.of(new WebhookPayload(ref, "SUCCEEDED", d.path("amount").asLong()));
    }

    /** Redirect: appid, apptransid, pmcid, bankcode, amount, discountamount, status, checksum. */
    @Override
    public Verified verifyReturn(CallbackRequest req) {
        String ref = req.param("apptransid");
        String checksum = req.param("checksum");
        if (ref == null || checksum == null) {
            throw new IllegalArgumentException("Missing ZaloPay redirect fields");
        }
        String raw = String.join("|", p(req, "appid"), ref, p(req, "pmcid"), p(req, "bankcode"), p(req, "amount"),
                p(req, "discountamount"), p(req, "status"));
        if (!Hmac.hexEquals(Hmac.sha256Hex(cfg.key2(), raw), checksum)) {
            return Verified.INVALID;
        }
        long amount;
        try {
            amount = Long.parseLong(p(req, "amount"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad ZaloPay amount");
        }
        return Verified.of(new WebhookPayload(ref, "1".equals(p(req, "status")) ? "SUCCEEDED" : "FAILED", amount));
    }

    private static String p(CallbackRequest req, String k) {
        return req.params().getOrDefault(k, "");
    }

    /** return_code 1 = done, 2 = already done; 0 makes ZaloPay retry. */
    @Override
    public Ack ack(Outcome outcome) {
        return switch (outcome) {
            case OK, FAILED, PENDING -> new Ack(200, Map.of("return_code", 1, "return_message", "success"));
            case DUPLICATE -> new Ack(200, Map.of("return_code", 2, "return_message", "already processed"));
            case INVALID_SIGNATURE -> new Ack(200, Map.of("return_code", -1, "return_message", "mac not equal"));
            case UNKNOWN_REF, AMOUNT_MISMATCH, MALFORMED ->
                    new Ack(200, Map.of("return_code", 0, "return_message", outcome.name().toLowerCase()));
        };
    }
}
