package com.rentgg.payment.infrastructure.psp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rentgg.config.RentGgProperties.Payment;
import com.rentgg.payment.application.PaymentGateway.CheckoutRequest;
import com.rentgg.payment.application.PaymentGateway.ProviderException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Failure modes of the outbound PSP call. Each failing call is made once and not retried: the request count is the
 * evidence. Retries are left to the client, which resends with the same idempotency key.
 */
class PspHttpFailureTest {

    private MockWebServer server;
    private PayosGateway gateway;
    private PspHttp http;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
        String endpoint = server.url("").toString().replaceAll("/$", "");
        http = new PspHttp(new ObjectMapper());
        gateway = new PayosGateway(PspTestProps.withPayos(
                new Payment.Payos("c", "k", PayosGatewayContractTest.CHECKSUM, endpoint)), http);
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private CheckoutRequest request() {
        return new CheckoutRequest(UUID.randomUUID(), 150_000, "VieGG wallet top-up", "https://rentgg.test/r",
                "https://rentgg.test/w", "10.0.0.7", Instant.now().plusSeconds(900));
    }

    @Test
    void fiveHundredWithHtmlBodyIsOneAttemptAndAProviderException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("<html>Bad gateway</html>"));
        assertThatThrownBy(() -> gateway.createCheckout(request())).isInstanceOf(ProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void fiveHundredWithEmptyBodyIsOneAttemptAndAProviderException() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody(""));
        assertThatThrownBy(() -> gateway.createCheckout(request())).isInstanceOf(ProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void droppedConnectionIsOneAttemptAndAProviderException() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThatThrownBy(() -> gateway.createCheckout(request())).isInstanceOf(ProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void readTimeoutAfterTenSecondsIsOneAttemptAndAProviderException() {
        server.enqueue(new MockResponse().setHeadersDelay(11, TimeUnit.SECONDS).setBody("{}"));
        long started = System.nanoTime();
        assertThatThrownBy(() -> http.postJson(server.url("/v2/payment-requests").toString(),
                Map.of("x", 1), Map.of())).isInstanceOf(ProviderException.class);
        long took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(took).isBetween(9_000L, 20_000L);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    /**
     * Probe: a 500 whose body looks like success. The adapter never reads the HTTP status itself; the request factory
     * (HttpURLConnection) raises an IOException on 5xx, so the call still fails. That protection comes from the factory,
     * not from the adapter: if the factory changes, this test is the first to show it.
     */
    @Test
    void fiveHundredWithASuccessShapedBodyStillFailsBecauseTheFactoryRaisesOn5xx() {
        server.enqueue(new MockResponse().setResponseCode(500).setHeader("Content-Type", "application/json")
                .setBody("{\"code\":\"00\",\"data\":{\"checkoutUrl\":\"https://pay.payos.vn/web/x\"}}"));
        assertThatThrownBy(() -> gateway.createCheckout(request())).isInstanceOf(ProviderException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
