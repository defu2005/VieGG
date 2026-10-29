package com.rentgg;

import com.rentgg.payment.domain.PaymentIntent;
import com.rentgg.payment.infrastructure.PaymentIntentRepository;
import com.rentgg.shared.crypto.Hmac;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Several payment providers side by side: listing, VNPay (signed GET IPN + return), ZaloPay (signed callback). */
class PaymentProvidersIT extends IntegrationTest {

    private static final String VNPAY_SECRET = "TESTVNPAYHASHSECRET0123456789ABCD";
    private static final String ZALOPAY_KEY2 = "test-zalopay-key2";

    @Autowired
    PaymentIntentRepository intentRepo;

    @Test
    void onlyConfiguredProvidersAreOffered() throws Exception {
        UUID u = user();
        mvc.perform(get("/api/v1/payments/providers").header("Authorization", bearer(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("FAKE"))
                .andExpect(jsonPath("$[?(@.code == 'VNPAY')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'ZALOPAY')]").exists())
                .andExpect(jsonPath("$[?(@.code == 'MOMO')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.code == 'PAYOS')]").doesNotExist());

        mvc.perform(post("/api/v1/wallet/topups").header("Authorization", bearer(u))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100000,\"provider\":\"PAYOS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));
        mvc.perform(post("/api/v1/payments/webhook/momo").content("{}")).andExpect(status().isNotFound());
    }

    @Test
    void vnpayCheckoutIpnAndReturn() throws Exception {
        UUID u = user();
        var intent = payments.createTopup(u, 150_000, UUID.randomUUID().toString(), "vnpay", "10.0.0.7");
        assertThat(intent.provider()).isEqualTo("VNPAY");

        // The checkout URL is signed over its own sorted query.
        String url = intent.checkoutUrl();
        assertThat(url).startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        String query = url.substring(url.indexOf('?') + 1);
        String signed = query.substring(0, query.indexOf("&vnp_SecureHash="));
        assertThat(query.substring(query.indexOf("&vnp_SecureHash=") + 16)).isEqualTo(Hmac.sha512Hex(VNPAY_SECRET, signed));
        Map<String, String> sent = UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap();
        assertThat(sent.get("vnp_Amount")).isEqualTo("15000000");
        assertThat(sent.get("vnp_IpAddr")).isEqualTo("10.0.0.7");
        assertThat(URLDecoder.decode(sent.get("vnp_ReturnUrl"), StandardCharsets.UTF_8))
                .endsWith("/api/v1/payments/return/vnpay");

        Map<String, String> result = new TreeMap<>(Map.of("vnp_TxnRef", intent.providerRef(), "vnp_Amount", "15000000",
                "vnp_ResponseCode", "00", "vnp_TransactionStatus", "00", "vnp_TmnCode", "TESTTMN1",
                "vnp_OrderInfo", "NapviVieGG", "vnp_TransactionNo", "14000001"));

        // Tampered amount → 97, nothing credited.
        Map<String, String> tampered = new TreeMap<>(result);
        String goodHash = Hmac.sha512Hex(VNPAY_SECRET, encode(result));
        tampered.put("vnp_Amount", "99900000");
        mvc.perform(get("/api/v1/payments/webhook/vnpay?" + encode(tampered) + "&vnp_SecureHash=" + goodHash))
                .andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("97"));
        assertThat(walletBalance(u)).isZero();

        String ipn = "/api/v1/payments/webhook/vnpay?" + encode(result) + "&vnp_SecureHash=" + goodHash;
        mvc.perform(get(ipn)).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));
        mvc.perform(get(ipn)).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("02"));
        assertThat(walletBalance(u)).isEqualTo(150_000);

        // Browser return with the same signed params → wallet page with the result (idempotent: no double credit).
        mvc.perform(get("/api/v1/payments/return/vnpay?" + encode(result) + "&vnp_SecureHash=" + goodHash))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", startsWith("http://localhost:5273/wallet?paymentResult=SUCCEEDED&payment=" + intent.id())));
        assertThat(walletBalance(u)).isEqualTo(150_000);
        assertThat(totalLedger()).isZero();

        // Bad signature on return → wallet page, result unknown.
        mvc.perform(get("/api/v1/payments/return/vnpay?vnp_TxnRef=x&vnp_Amount=1&vnp_SecureHash=00"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:5273/wallet?paymentResult=UNKNOWN"));
    }

    @Test
    void zalopayCallback() throws Exception {
        UUID u = user();
        String transId = "261029_" + UUID.randomUUID().toString().replace("-", "");
        PaymentIntent pi = new PaymentIntent(null, u, "ZALOPAY", 80_000, UUID.randomUUID().toString(), now());
        pi.attachCheckout(transId, "https://sb-openapi.zalopay.vn/pay", now());
        intentRepo.saveAndFlush(pi);

        String data = "{\"app_id\":2553,\"app_trans_id\":\"" + transId + "\",\"amount\":80000,\"zp_trans_id\":1}";
        String body = "{\"data\":" + quote(data) + ",\"mac\":\"" + Hmac.sha256Hex(ZALOPAY_KEY2, data) + "\",\"type\":1}";
        String bad = "{\"data\":" + quote(data) + ",\"mac\":\"deadbeef\",\"type\":1}";

        mvc.perform(post("/api/v1/payments/webhook/zalopay").contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return_code").value(-1));
        assertThat(walletBalance(u)).isZero();

        mvc.perform(post("/api/v1/payments/webhook/zalopay").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return_code").value(1));
        mvc.perform(post("/api/v1/payments/webhook/zalopay").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.return_code").value(2));
        assertThat(walletBalance(u)).isEqualTo(80_000);
        assertThat(totalLedger()).isZero();
    }

    private static String encode(Map<String, String> sorted) {
        return sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.US_ASCII))
                .collect(Collectors.joining("&"));
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
