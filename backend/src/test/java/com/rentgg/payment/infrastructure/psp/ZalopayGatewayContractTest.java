package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.config.RentGgProperties.Payment;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.payment.application.PaymentGateway.CallbackRequest;
import com.rentgg.payment.application.PaymentGateway.CheckoutRequest;
import com.rentgg.payment.application.PaymentGateway.ProviderException;
import com.rentgg.support.TestHmac;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ZaloPay adapter against a mock server (no Spring). mac values are spelled out here, not taken from the adapter. */
class ZalopayGatewayContractTest {

    static final String APP_ID = "2553";
    static final String KEY1 = "contract-zalo-key1";
    static final String KEY2 = "contract-zalo-key2";
    static final String RETURN = "https://rentgg.test/return/zalopay";
    static final String CALLBACK = "https://rentgg.test/webhook/zalopay";

    private MockWebServer server;
    private ZalopayGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        String endpoint = server.url("").toString().replaceAll("/$", "");
        gateway = new ZalopayGateway(PspTestProps.withZalopay(new Payment.Zalopay(APP_ID, KEY1, KEY2, endpoint)),
                new PspHttp(json));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private CheckoutRequest request(long amount) {
        return new CheckoutRequest(UUID.randomUUID(), amount, "VieGG wallet top-up", RETURN, CALLBACK, "10.0.0.7",
                Instant.now().plusSeconds(900));
    }

    private static Map<String, String> form(String body) throws Exception {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    // ---- Outbound (form post)

    @Test
    void createCheckoutPostsAFormSignedWithKey1OverThePipeJoinedFields() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"return_code\":1,\"order_url\":\"https://sb-openapi.zalopay.vn/order/x\"}"));

        var checkout = gateway.createCheckout(request(80_000));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v2/create");
        Map<String, String> f = form(sent.getBody().readUtf8());
        assertThat(f.get("app_id")).isEqualTo(APP_ID);
        assertThat(f.get("amount")).isEqualTo("80000");
        assertThat(f.get("embed_data")).isEqualTo("{\"redirecturl\":\"" + RETURN + "\"}");
        assertThat(f.get("callback_url")).isEqualTo(CALLBACK);
        // appid|apptransid|appuser|amount|apptime|embeddata|item, signed with key1.
        String canonical = String.join("|", APP_ID, f.get("app_trans_id"), "VieGG", "80000", f.get("app_time"),
                f.get("embed_data"), "[]");
        assertThat(f.get("mac")).isEqualTo(TestHmac.sha256Hex(KEY1, canonical));

        assertThat(checkout.checkoutUrl()).isEqualTo("https://sb-openapi.zalopay.vn/order/x");
        assertThat(checkout.providerRef()).isEqualTo(f.get("app_trans_id"));
    }

    @Test
    void providerRefusalBecomesAProviderExceptionWithItsMessage() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"return_code\":-49,\"return_message\":\"Tham so khong hop le\"}"));
        assertThatThrownBy(() -> gateway.createCheckout(request(80_000)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("Tham so khong hop le");
    }

    /** postForm has no status check of its own: a 5xx fails in the request factory and is attempted once. */
    @Test
    void fiveHundredOnTheFormPostIsOneAttemptAndAProviderException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("<html>down</html>"));
        assertThatThrownBy(() -> gateway.createCheckout(request(80_000))).isInstanceOf(ProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    // ---- Inbound: server callback (data string + mac with key2)

    private String callbackBody(String data, String mac) throws IOException {
        var node = json.createObjectNode();
        node.put("data", data);
        if (mac != null) {
            node.put("mac", mac);
        }
        node.put("type", 1);
        return json.writeValueAsString(node);
    }

    private static String data(String transId, long amount) {
        return "{\"app_id\":2553,\"app_trans_id\":\"" + transId + "\",\"amount\":" + amount + ",\"zp_trans_id\":1}";
    }

    private CallbackRequest callback(String body) {
        return new CallbackRequest("POST", Map.of(), Map.of("content-type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validCallbackIsAcceptedAndTheTamperedOrWrongKeyOnesAreNot() throws Exception {
        String d = data("261029_abc", 80_000);
        String mac = TestHmac.sha256Hex(KEY2, d);

        var ok = gateway.verifyCallback(callback(callbackBody(d, mac)));
        assertThat(ok.signatureValid()).isTrue();
        assertThat(ok.payload().providerRef()).isEqualTo("261029_abc");
        assertThat(ok.payload().amount()).isEqualTo(80_000);

        String tampered = data("261029_abc", 999_000);
        assertThat(gateway.verifyCallback(callback(callbackBody(tampered, mac))).signatureValid()).isFalse();
        assertThat(gateway.verifyCallback(callback(callbackBody(d, TestHmac.sha256Hex(KEY1, d)))).signatureValid())
                .as("mac made with key1 instead of key2").isFalse();
    }

    @Test
    void missingMacIsMalformed() throws Exception {
        String d = data("261029_abc", 80_000);
        assertThatThrownBy(() -> gateway.verifyCallback(callback(callbackBody(d, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Inbound: browser redirect, checksum over appid|apptransid|pmcid|bankcode|amount|discountamount|status

    private CallbackRequest redirect(String status, String checksum, String amount) {
        Map<String, String> p = new HashMap<>();
        p.put("appid", APP_ID);
        p.put("apptransid", "261029_abc");
        p.put("pmcid", "38");
        p.put("bankcode", "");
        p.put("amount", amount);
        p.put("discountamount", "0");
        p.put("status", status);
        p.put("checksum", checksum);
        return new CallbackRequest("GET", p, Map.of(), new byte[0]);
    }

    @Test
    void signedRedirectIsVerifiedAndItsStatusMapped() {
        String canonical = String.join("|", APP_ID, "261029_abc", "38", "", "80000", "0", "1");
        var paid = gateway.verifyReturn(redirect("1", TestHmac.sha256Hex(KEY2, canonical), "80000"));
        assertThat(paid.signatureValid()).isTrue();
        assertThat(paid.payload().status()).isEqualTo("SUCCEEDED");

        String failedCanonical = String.join("|", APP_ID, "261029_abc", "38", "", "80000", "0", "2");
        assertThat(gateway.verifyReturn(redirect("2", TestHmac.sha256Hex(KEY2, failedCanonical), "80000"))
                .payload().status()).isEqualTo("FAILED");
    }

    @Test
    void redirectWithAChangedAmountIsRejected() {
        String canonical = String.join("|", APP_ID, "261029_abc", "38", "", "80000", "0", "1");
        assertThat(gateway.verifyReturn(redirect("1", TestHmac.sha256Hex(KEY2, canonical), "999000")).signatureValid())
                .isFalse();
    }
}
