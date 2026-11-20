package com.rentgg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rentgg.payment.domain.PaymentIntent;
import com.rentgg.payment.infrastructure.PaymentIntentRepository;
import com.rentgg.support.IntegrationTest;
import com.rentgg.support.TestHmac;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MoMo IPN through the real endpoint (default context). Keys are the ones in application-test.yml. */
class MomoWebhookIT extends IntegrationTest {

    private static final String ACCESS = "test-momo-access-key";
    private static final String SECRET = "test-momo-secret-key";
    private static final String PARTNER = "TESTMOMOPARTNER";
    private static final String[] RESULT = {"amount", "extraData", "message", "orderId", "orderInfo", "orderType",
            "partnerCode", "payType", "requestId", "responseTime", "resultCode", "transId"};

    @Autowired PaymentIntentRepository intentRepo;

    private String ipn(String orderId, String amount, String resultCode, String key) throws Exception {
        ObjectMapper m = new ObjectMapper();
        ObjectNode n = m.createObjectNode();
        n.put("amount", amount);
        n.put("extraData", "");
        n.put("message", "Successful.");
        n.put("orderId", orderId);
        n.put("orderInfo", "Nap vi VieGG");
        n.put("orderType", "momo_wallet");
        n.put("partnerCode", PARTNER);
        n.put("payType", "webApp");
        n.put("requestId", orderId);
        n.put("responseTime", "1700000000000");
        n.put("resultCode", resultCode);
        n.put("transId", "2700000002");
        StringBuilder raw = new StringBuilder("accessKey=" + ACCESS);
        for (String k : RESULT) {
            raw.append('&').append(k).append('=').append(n.get(k).asText());
        }
        n.put("signature", TestHmac.sha256Hex(key, raw.toString()));
        return m.writeValueAsString(n);
    }

    private PaymentIntent pending(UUID payer, long amount, String orderId) {
        PaymentIntent pi = new PaymentIntent(null, payer, "MOMO", amount, UUID.randomUUID().toString(), now());
        pi.attachCheckout(orderId, "https://test-payment.momo.vn/pay/x", now());
        return intentRepo.saveAndFlush(pi);
    }

    private org.springframework.test.web.servlet.ResultActions postIpn(String body) throws Exception {
        return mvc.perform(post("/api/v1/payments/webhook/momo").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void validIpnCreditsOnceAndAReplayIsAcknowledgedWithoutCrediting() throws Exception {
        UUID payer = user();
        String orderId = "RGG" + UUID.randomUUID().toString().replace("-", "");
        pending(payer, 120_000, orderId);
        String body = ipn(orderId, "120000", "0", SECRET);

        postIpn(body).andExpect(status().isNoContent());
        assertThat(walletBalance(payer)).isEqualTo(120_000);

        postIpn(body).andExpect(status().isNoContent());
        assertThat(walletBalance(payer)).isEqualTo(120_000);
        assertThat(totalLedger()).isZero();
    }

    @Test
    void badSignatureIsRefusedWithBadRequestAndChangesNothing() throws Exception {
        UUID payer = user();
        String orderId = "RGG" + UUID.randomUUID().toString().replace("-", "");
        PaymentIntent pi = pending(payer, 120_000, orderId);

        postIpn(ipn(orderId, "120000", "0", "not-the-real-secret")).andExpect(status().isBadRequest());

        assertThat(walletBalance(payer)).isZero();
        assertThat(intentRepo.findById(pi.getId()).orElseThrow().getStatus()).isEqualTo("CREATED");
    }
}
