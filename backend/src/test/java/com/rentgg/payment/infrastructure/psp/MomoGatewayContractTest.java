package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MoMo adapter against a mock server (no Spring). Signed strings are spelled out here, field by field. */
class MomoGatewayContractTest {

    static final String ACCESS = "contract-access-key";
    static final String SECRET = "contract-secret-key";
    static final String PARTNER = "CONTRACTMOMO";
    static final String RETURN = "https://rentgg.test/return/momo";
    static final String IPN = "https://rentgg.test/webhook/momo";

    private MockWebServer server;
    private MomoGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        String endpoint = server.url("").toString().replaceAll("/$", "");
        gateway = new MomoGateway(PspTestProps.withMomo(new Payment.Momo(PARTNER, ACCESS, SECRET, endpoint)),
                new PspHttp(json));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private CheckoutRequest request(long amount) {
        return new CheckoutRequest(UUID.randomUUID(), amount, "VieGG wallet top-up", RETURN, IPN, "10.0.0.7",
                Instant.now().plusSeconds(900));
    }

    // ---- Outbound

    @Test
    void createCheckoutSendsTheSignedRequestAndReturnsPayUrl() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"resultCode\":0,\"message\":\"Success\",\"payUrl\":\"https://test-payment.momo.vn/pay/x\"}"));

        var checkout = gateway.createCheckout(request(150_000));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v2/gateway/api/create");
        JsonNode body = json.readTree(sent.getBody().readUtf8());
        String orderId = body.get("orderId").asText();
        assertThat(body.get("partnerCode").asText()).isEqualTo(PARTNER);
        assertThat(body.get("requestType").asText()).isEqualTo("captureWallet");
        assertThat(body.get("amount").asLong()).isEqualTo(150_000);
        // Sorted by name, as the provider documents for captureWallet.
        String canonical = "accessKey=" + ACCESS + "&amount=150000&extraData=&ipnUrl=" + IPN + "&orderId=" + orderId
                + "&orderInfo=Nap vi VieGG&partnerCode=" + PARTNER + "&redirectUrl=" + RETURN
                + "&requestId=" + orderId + "&requestType=captureWallet";
        assertThat(body.get("signature").asText()).isEqualTo(TestHmac.sha256Hex(SECRET, canonical));

        assertThat(checkout.checkoutUrl()).isEqualTo("https://test-payment.momo.vn/pay/x");
        assertThat(checkout.providerRef()).isEqualTo(orderId);
    }

    @Test
    void providerRefusalBecomesAProviderExceptionWithItsMessage() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"resultCode\":1003,\"message\":\"Giao dich bi tu choi\"}"));
        assertThatThrownBy(() -> gateway.createCheckout(request(150_000)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("Giao dich bi tu choi");
    }

    // ---- Inbound

    private static final String[] RESULT = {"amount", "extraData", "message", "orderId", "orderInfo", "orderType",
            "partnerCode", "payType", "requestId", "responseTime", "resultCode", "transId"};

    /** Field values as the provider sends them; the canonical string is built from RESULT in its order. */
    private Map<String, String> result(String amount, String resultCode) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("amount", amount);
        f.put("extraData", "");
        f.put("message", "Successful.");
        f.put("orderId", "RGG00000000000000000000000000000001");
        f.put("orderInfo", "Nap vi VieGG");
        f.put("orderType", "momo_wallet");
        f.put("partnerCode", PARTNER);
        f.put("payType", "webApp");
        f.put("requestId", "RGG00000000000000000000000000000001");
        f.put("responseTime", "1700000000000");
        f.put("resultCode", resultCode);
        f.put("transId", "2700000001");
        return f;
    }

    private static String canonicalResult(Map<String, String> f) {
        StringBuilder raw = new StringBuilder("accessKey=" + ACCESS);
        for (String k : RESULT) {
            raw.append('&').append(k).append('=').append(f.get(k));
        }
        return raw.toString();
    }

    private String callbackBody(Map<String, String> f, String signature) throws IOException {
        var node = json.createObjectNode();
        for (String k : RESULT) {
            node.put(k, f.get(k));
        }
        node.put("signature", signature);
        return json.writeValueAsString(node);
    }

    private CallbackRequest callback(String body) {
        return new CallbackRequest("POST", Map.of(), Map.of("content-type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validCallbackIsAcceptedWithThePaidStatus() throws Exception {
        Map<String, String> f = result("150000", "0");
        String sig = TestHmac.sha256Hex(SECRET, canonicalResult(f));

        PaymentGateway.Verified v = gateway.verifyCallback(callback(callbackBody(f, sig)));

        assertThat(v.signatureValid()).isTrue();
        assertThat(v.payload().providerRef()).isEqualTo("RGG00000000000000000000000000000001");
        assertThat(v.payload().status()).isEqualTo("SUCCEEDED");
        assertThat(v.payload().amount()).isEqualTo(150_000);
    }

    @Test
    void oneTamperedFieldIsRejected() throws Exception {
        Map<String, String> signed = result("150000", "0");
        String sig = TestHmac.sha256Hex(SECRET, canonicalResult(signed));
        Map<String, String> tampered = result("999000", "0");

        assertThat(gateway.verifyCallback(callback(callbackBody(tampered, sig))).signatureValid()).isFalse();
    }

    @Test
    void signatureMadeWithAnotherKeyIsRejected() throws Exception {
        Map<String, String> f = result("150000", "0");
        String wrong = TestHmac.sha256Hex("some-other-secret", canonicalResult(f));

        assertThat(gateway.verifyCallback(callback(callbackBody(f, wrong))).signatureValid()).isFalse();
    }

    @Test
    void missingSignatureIsMalformed() throws Exception {
        Map<String, String> f = result("150000", "0");
        assertThatThrownBy(() -> gateway.verifyCallback(callback(callbackBody(f, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Signed return: the same fields and signature arrive as query parameters. */
    @Test
    void signedReturnIsVerifiedFromTheQueryParameters() {
        Map<String, String> f = result("150000", "0");
        String sig = TestHmac.sha256Hex(SECRET, canonicalResult(f));
        Map<String, String> params = new LinkedHashMap<>(f);
        params.put("signature", sig);

        PaymentGateway.Verified v = gateway.verifyReturn(new CallbackRequest("GET", params, Map.of(), new byte[0]));

        assertThat(v.signatureValid()).isTrue();
        assertThat(v.payload().status()).isEqualTo("SUCCEEDED");
    }

    @Test
    void waitingForThePayerIsPendingNotFailed() throws Exception {
        Map<String, String> f = result("150000", "1000");
        String sig = TestHmac.sha256Hex(SECRET, canonicalResult(f));

        assertThat(gateway.verifyCallback(callback(callbackBody(f, sig))).payload().status()).isEqualTo("PENDING");
    }
}
