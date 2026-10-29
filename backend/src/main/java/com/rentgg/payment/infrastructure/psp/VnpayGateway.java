package com.rentgg.payment.infrastructure.psp;

import com.rentgg.config.RentGgProperties;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.shared.crypto.Hmac;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * VNPay (API 2.1.0). Free sandbox: register at https://sandbox.vnpayment.vn/devreg to get a TMN code and hash
 * secret. Payer is redirected to the VNPay page; result comes back signed (HMAC-SHA512) on both the return URL and
 * the IPN (GET). The IPN URL is set in the VNPay merchant portal: {@code <public>/api/v1/payments/webhook/vnpay}.
 */
@Component
public class VnpayGateway implements PaymentGateway {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RentGgProperties.Payment.Vnpay cfg;

    public VnpayGateway(RentGgProperties props) {
        this.cfg = props.payment().vnpay();
    }

    @Override
    public String provider() {
        return "VNPAY";
    }

    @Override
    public String displayName() {
        return "VNPay (ATM, QR, thẻ quốc tế)";
    }

    @Override
    public boolean isConfigured() {
        return cfg != null && !PspHttp.blank(cfg.tmnCode()) && !PspHttp.blank(cfg.hashSecret());
    }

    @Override
    public boolean sandbox() {
        return cfg != null && cfg.payUrl() != null && cfg.payUrl().contains("sandbox");
    }

    @Override
    public Checkout createCheckout(CheckoutRequest req) {
        String ref = PspHttp.compact(req.intentId());
        ZonedDateTime now = ZonedDateTime.now(VN);
        ZonedDateTime expires = req.expiresAt() == null ? now.plusMinutes(15) : req.expiresAt().atZone(VN);
        Map<String, String> p = new TreeMap<>();
        p.put("vnp_Version", "2.1.0");
        p.put("vnp_Command", "pay");
        p.put("vnp_TmnCode", cfg.tmnCode());
        p.put("vnp_Amount", String.valueOf(req.amount() * 100));
        p.put("vnp_CurrCode", "VND");
        p.put("vnp_TxnRef", ref);
        p.put("vnp_OrderInfo", "Nap vi VieGG " + ref.substring(0, 8));
        p.put("vnp_OrderType", "other");
        p.put("vnp_Locale", "vn");
        p.put("vnp_ReturnUrl", req.returnUrl());
        p.put("vnp_IpAddr", PspHttp.blank(req.clientIp()) ? "127.0.0.1" : req.clientIp());
        p.put("vnp_CreateDate", TS.format(now));
        p.put("vnp_ExpireDate", TS.format(expires));
        String query = encode(p);
        String url = cfg.payUrl() + "?" + query + "&vnp_SecureHash=" + Hmac.sha512Hex(cfg.hashSecret(), query);
        return new Checkout(ref, url);
    }

    @Override
    public Verified verifyCallback(CallbackRequest req) {
        String hash = req.param("vnp_SecureHash");
        String ref = req.param("vnp_TxnRef");
        if (hash == null || ref == null || req.param("vnp_Amount") == null) {
            throw new IllegalArgumentException("Missing VNPay fields");
        }
        Map<String, String> signed = new TreeMap<>();
        req.params().forEach((k, v) -> {
            if (k.startsWith("vnp_") && !k.equals("vnp_SecureHash") && !k.equals("vnp_SecureHashType")
                    && v != null && !v.isEmpty()) {
                signed.put(k, v);
            }
        });
        if (!Hmac.hexEquals(Hmac.sha512Hex(cfg.hashSecret(), encode(signed)), hash)) {
            return Verified.INVALID;
        }
        long amount;
        try {
            amount = Long.parseLong(req.param("vnp_Amount")) / 100;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad vnp_Amount");
        }
        boolean ok = "00".equals(req.param("vnp_ResponseCode")) && "00".equals(req.param("vnp_TransactionStatus"));
        return Verified.of(new WebhookPayload(ref, ok ? "SUCCEEDED" : "FAILED", amount));
    }

    /** The return URL carries the same signed parameters as the IPN. */
    @Override
    public Verified verifyReturn(CallbackRequest req) {
        return verifyCallback(req);
    }

    /** VNPay expects HTTP 200 with an RspCode for every IPN. */
    @Override
    public Ack ack(Outcome outcome) {
        String[] r = switch (outcome) {
            case OK, FAILED, PENDING -> new String[]{"00", "Confirm Success"};
            case DUPLICATE -> new String[]{"02", "Order already confirmed"};
            case UNKNOWN_REF -> new String[]{"01", "Order not found"};
            case AMOUNT_MISMATCH -> new String[]{"04", "invalid amount"};
            case INVALID_SIGNATURE -> new String[]{"97", "Invalid Checksum"};
            case MALFORMED -> new String[]{"99", "Unknow error"};
        };
        return new Ack(200, Map.of("RspCode", r[0], "Message", r[1]));
    }

    /** Sorted {@code key=urlencoded(value)} joined by '&' — both the query string and the signed data. */
    static String encode(Map<String, String> sorted) {
        return sorted.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.US_ASCII) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }
}
