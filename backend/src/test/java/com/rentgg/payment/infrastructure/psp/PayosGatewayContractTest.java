package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PayOS adapter against a mock server (no Spring context). Expected signatures are computed here from literal
 * canonical strings with javax.crypto, never with the adapter's own helper.
 */
class PayosGatewayContractTest {

    static final String CHECKSUM = "contract-checksum-key";
    static final String CLIENT = "contract-client-id";
    static final String API_KEY = "contract-api-key";

    private MockWebServer server;
    private PayosGateway gateway;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        String endpoint = server.url("").toString().replaceAll("/$", "");
        gateway = new PayosGateway(PspTestProps.withPayos(new Payment.Payos(CLIENT, API_KEY, CHECKSUM, endpoint)),
                new PspHttp(json));
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    static String hmacSha256Hex(String key, String text) {
        return TestHmac.sha256Hex(key, text);
    }

    private CheckoutRequest checkoutRequest(long amount) {
        return new CheckoutRequest(UUID.randomUUID(), amount, "VieGG wallet top-up",
                "https://rentgg.test/return/payos", "https://rentgg.test/webhook/payos", "10.0.0.7",
                Instant.now().plusSeconds(900));
    }

    // ---- Outbound: checkout creation

    @Test
    void createCheckoutSendsTheDocumentedRequestSignedWithTheChecksumKey() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"checkoutUrl\":\"https://pay.payos.vn/web/abc\"}}"));

        PaymentGateway.Checkout checkout = gateway.createCheckout(checkoutRequest(150_000));

        RecordedRequest sent = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(sent).isNotNull();
        assertThat(sent.getMethod()).isEqualTo("POST");
        assertThat(sent.getPath()).isEqualTo("/v2/payment-requests");
        assertThat(sent.getHeader("x-client-id")).isEqualTo(CLIENT);
        assertThat(sent.getHeader("x-api-key")).isEqualTo(API_KEY);

        JsonNode body = json.readTree(sent.getBody().readUtf8());
        long orderCode = body.get("orderCode").asLong();
        assertThat(body.get("amount").asLong()).isEqualTo(150_000);
        assertThat(body.get("description").asText()).isEqualTo("Nap vi");
        assertThat(body.get("returnUrl").asText()).isEqualTo("https://rentgg.test/return/payos");
        // The canonical string the adapter signs: fields sorted by name, joined by '&'.
        String canonical = "amount=150000&cancelUrl=https://rentgg.test/return/payos&description=Nap vi"
                + "&orderCode=" + orderCode + "&returnUrl=https://rentgg.test/return/payos";
        assertThat(body.get("signature").asText()).isEqualTo(hmacSha256Hex(CHECKSUM, canonical));

        assertThat(checkout.checkoutUrl()).isEqualTo("https://pay.payos.vn/web/abc");
        assertThat(checkout.providerRef()).isEqualTo(String.valueOf(orderCode));
    }

    @Test
    void providerRefusalBecomesAProviderExceptionWithItsReason() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"101\",\"desc\":\"Sai chu ky\"}"));
        assertThatThrownBy(() -> gateway.createCheckout(checkoutRequest(150_000)))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("Sai chu ky");
    }

    // ---- Inbound: callback signature and payload

    /** Data with a null field and an unknown extra field: both are part of the signed string. */
    private ObjectNode data(long orderCode, long amount) {
        ObjectNode d = json.createObjectNode();
        d.put("orderCode", orderCode);
        d.put("amount", amount);
        d.put("code", "00");
        d.put("desc", "success");
        d.putNull("reference");
        d.put("unknownField", "x");
        return d;
    }

    /** Sorted by key, null as empty, joined by '&': the canonical string of the callback's data object. */
    private static String canonicalCallback(ObjectNode d) {
        return "amount=" + d.get("amount").asText() + "&code=" + d.get("code").asText() + "&desc=" + d.get("desc").asText()
                + "&orderCode=" + d.get("orderCode").asText() + "&reference=" + "" + "&unknownField=" + d.get("unknownField").asText();
    }

    private String callback(ObjectNode data, String signature) {
        ObjectNode env = json.createObjectNode();
        env.put("code", "00");
        env.put("desc", "success");
        env.put("success", true);
        env.set("data", data);
        if (signature != null) {
            env.put("signature", signature);
        }
        return env.toString();
    }

    private CallbackRequest req(String body) {
        return new CallbackRequest("POST", Map.of(), Map.of("content-type", "application/json"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validCallbackIsAcceptedWithAPaidStatus() throws Exception {
        ObjectNode d = data(4_242_000_111L, 150_000);
        String sig = hmacSha256Hex(CHECKSUM, canonicalCallback(d));

        PaymentGateway.Verified v = gateway.verifyCallback(req(callback(d, sig)));

        assertThat(v.signatureValid()).isTrue();
        assertThat(v.payload().providerRef()).isEqualTo("4242000111");
        assertThat(v.payload().status()).isEqualTo("SUCCEEDED");
        assertThat(v.payload().amount()).isEqualTo(150_000);
    }

    @Test
    void oneTamperedFieldIsRejected() throws Exception {
        ObjectNode d = data(4_242_000_111L, 150_000);
        String sig = hmacSha256Hex(CHECKSUM, canonicalCallback(d));
        ObjectNode tampered = data(4_242_000_111L, 999_000);

        assertThat(gateway.verifyCallback(req(callback(tampered, sig))).signatureValid()).isFalse();
    }

    @Test
    void signatureMadeWithAnotherKeyIsRejected() throws Exception {
        ObjectNode d = data(4_242_000_111L, 150_000);
        String wrongKey = hmacSha256Hex("some-other-key", canonicalCallback(d));

        assertThat(gateway.verifyCallback(req(callback(d, wrongKey))).signatureValid()).isFalse();
    }

    @Test
    void missingSignatureIsMalformed() {
        ObjectNode d = data(4_242_000_111L, 150_000);
        assertThatThrownBy(() -> gateway.verifyCallback(req(callback(d, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    // ---- Return: the redirect has no signature, so the status is asked from the provider

    private CallbackRequest returnRequest(String orderCode) {
        return new CallbackRequest("GET", Map.of("orderCode", orderCode), Map.of(), new byte[0]);
    }

    private void answerStatus(String status) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"00\",\"data\":{\"status\":\"" + status + "\",\"amount\":150000}}"));
    }

    @Test
    void returnAsksThePaymentStatusAndMapsIt() throws Exception {
        answerStatus("PAID");
        PaymentGateway.Verified v = gateway.verifyReturn(returnRequest("4242000111"));

        RecordedRequest asked = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(asked.getMethod()).isEqualTo("GET");
        assertThat(asked.getPath()).isEqualTo("/v2/payment-requests/4242000111");
        assertThat(asked.getHeader("x-client-id")).isEqualTo(CLIENT);
        assertThat(asked.getHeader("x-api-key")).isEqualTo(API_KEY);
        assertThat(v.signatureValid()).isTrue();
        assertThat(v.payload().status()).isEqualTo("SUCCEEDED");
        assertThat(v.payload().amount()).isEqualTo(150_000);
    }

    @Test
    void returnStatusesMapToSucceededFailedOrPending() {
        answerStatus("CANCELLED");
        assertThat(gateway.verifyReturn(returnRequest("1")).payload().status()).isEqualTo("FAILED");
        answerStatus("EXPIRED");
        assertThat(gateway.verifyReturn(returnRequest("2")).payload().status()).isEqualTo("FAILED");
        answerStatus("PENDING");
        assertThat(gateway.verifyReturn(returnRequest("3")).payload().status()).isEqualTo("PENDING");
    }

    @Test
    void aNonSuccessCodeFromTheStatusCallIsNotVerified() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"101\",\"desc\":\"Sai khoa\"}"));
        assertThat(gateway.verifyReturn(returnRequest("4")).signatureValid()).isFalse();
    }
}
