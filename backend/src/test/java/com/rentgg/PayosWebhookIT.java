package com.rentgg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.payment.domain.PaymentIntent;
import com.rentgg.payment.infrastructure.PaymentIntentRepository;
import com.rentgg.shared.error.ApiException;
import com.rentgg.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PayOS through the real webhook endpoint (default context, Postgres). Signatures are computed here from a literal
 * canonical string; the key is the one in application-test.yml.
 */
class PayosWebhookIT extends IntegrationTest {

    private static final String CHECKSUM = "test-payos-checksum-key";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired PaymentIntentRepository intentRepo;
    @Autowired PaymentService payments;

    /** Sorted fields, null as empty, joined by '&'. Built by hand here, not with the adapter's helper. */
    private static String canonical(long orderCode, long amount) {
        return "amount=" + amount + "&code=00&desc=success&orderCode=" + orderCode + "&reference=&unknownField=x";
    }

    /** HMAC-SHA256 hex, written here with javax.crypto so the test does not reuse the adapter's helper. */
    private static String sign(String key, String text) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private String webhook(long orderCode, long amount, String key) throws Exception {
        ObjectNode data = JSON.createObjectNode();
        data.put("orderCode", orderCode);
        data.put("amount", amount);
        data.put("code", "00");
        data.put("desc", "success");
        data.putNull("reference");
        data.put("unknownField", "x");
        ObjectNode env = JSON.createObjectNode();
        env.put("code", "00");
        env.put("desc", "success");
        env.put("success", true);
        env.set("data", data);
        env.put("signature", sign(key, canonical(orderCode, amount)));
        return JSON.writeValueAsString(env);
    }

    private PaymentIntent pendingTopup(UUID payer, long amount, long orderCode) {
        PaymentIntent pi = new PaymentIntent(null, payer, "PAYOS", amount, UUID.randomUUID().toString(), now());
        pi.attachCheckout(String.valueOf(orderCode), "https://pay.payos.vn/web/x", now());
        return intentRepo.saveAndFlush(pi);
    }

    private org.springframework.test.web.servlet.ResultActions postWebhook(String body) throws Exception {
        return mvc.perform(post("/api/v1/payments/webhook/payos").contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void validWebhookCreditsOnceAndAReplayIsAcknowledgedWithoutCrediting() throws Exception {
        UUID payer = user();
        long orderCode = 7_000_000_001L;
        PaymentIntent pi = pendingTopup(payer, 150_000, orderCode);
        String body = webhook(orderCode, 150_000, CHECKSUM);

        postWebhook(body).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(walletBalance(payer)).isEqualTo(150_000);

        postWebhook(body).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(walletBalance(payer)).isEqualTo(150_000);
        assertThat(intentRepo.findById(pi.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(totalLedger()).isZero();
    }

    @Test
    void badSignatureIsAnsweredWithFailureAuditedAndChangesNothing() throws Exception {
        UUID payer = user();
        long orderCode = 7_000_000_002L;
        PaymentIntent pi = pendingTopup(payer, 150_000, orderCode);
        String forged = webhook(orderCode, 150_000, "not-the-real-key");

        long auditBefore = jdbc.queryForObject("select count(*) from audit_logs where action = 'WEBHOOK_SIGNATURE_INVALID'",
                Long.class);
        postWebhook(forged).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));

        assertThat(walletBalance(payer)).isZero();
        assertThat(intentRepo.findById(pi.getId()).orElseThrow().getStatus()).isEqualTo("CREATED");
        long auditAfter = jdbc.queryForObject("select count(*) from audit_logs where action = 'WEBHOOK_SIGNATURE_INVALID'",
                Long.class);
        assertThat(auditAfter).isEqualTo(auditBefore + 1);
    }

    @Test
    void concurrentReplaysCreditExactlyOnce() throws Exception {
        UUID payer = user();
        long orderCode = 7_000_000_003L;
        pendingTopup(payer, 150_000, orderCode);
        String body = webhook(orderCode, 150_000, CHECKSUM);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return postWebhook(body).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        for (Future<Integer> f : results) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();
        assertThat(walletBalance(payer)).isEqualTo(150_000);
    }

    /** Unreachable provider: 502, no balance change, and the same idempotency key can be sent again. */
    @Test
    void topupThroughAnUnreachableProviderIsA502AndKeepsTheBalance() {
        UUID payer = user();
        String key = UUID.randomUUID().toString();
        // application-test.yml points PAYOS at a closed loopback port.
        ApiException first = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> payments.createTopup(payer, 100_000, key, "payos", "10.0.0.7"));
        assertThat(first).isNotNull();
        assertThat(first.status().value()).isEqualTo(502);
        assertThat(first.code()).isEqualTo("PAYMENT_PROVIDER_ERROR");
        assertThat(walletBalance(payer)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payment_intents where payer_id = ? and checkout_url is null",
                Long.class, payer)).isEqualTo(1L);

        ApiException second = (ApiException) org.assertj.core.api.Assertions.catchThrowable(
                () -> payments.createTopup(payer, 100_000, key, "payos", "10.0.0.7"));
        assertThat(second.status().value()).isEqualTo(502);
        assertThat(walletBalance(payer)).isZero();
    }
}
