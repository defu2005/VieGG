package com.rentgg.payment.infrastructure.psp;

import com.rentgg.config.RentGgProperties.Payment;
import com.rentgg.payment.application.PaymentGateway.CheckoutRequest;
import com.rentgg.support.TestHmac;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VNPay has no HTTP client to mock: the checkout is a URL, so the test checks the URL it builds and recomputes the
 * HMAC-SHA512 over the query text without the hash parameter.
 */
class VnpayGatewayContractTest {

    private static final String SECRET = "contract-vnpay-hash-secret";
    private static final String PAY_URL = "https://sandbox.vnpayment.vn/paymentv2/vpcpay.html";

    private final VnpayGateway gateway = new VnpayGateway(PspTestProps.withVnpay(
            new Payment.Vnpay("CONTRACTTMN", SECRET, PAY_URL)));

    @Test
    void checkoutUrlCarriesTheDocumentedParametersAndAHashOverItsQuery() {
        var checkout = gateway.createCheckout(new CheckoutRequest(UUID.randomUUID(), 150_000, "VieGG", "https://rentgg.test/return/vnpay",
                "https://rentgg.test/webhook/vnpay", "10.0.0.7", Instant.now().plusSeconds(900)));

        String url = checkout.checkoutUrl();
        assertThat(url).startsWith(PAY_URL + "?");
        String query = url.substring(url.indexOf('?') + 1);
        int cut = query.indexOf("&vnp_SecureHash=");
        String signedPart = query.substring(0, cut);
        String hash = query.substring(cut + "&vnp_SecureHash=".length());

        assertThat(hash).isEqualTo(TestHmac.sha512Hex(SECRET, signedPart));

        Map<String, String> params = new TreeMap<>();
        for (String pair : signedPart.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        assertThat(params.get("vnp_TmnCode")).isEqualTo("CONTRACTTMN");
        assertThat(params.get("vnp_Amount")).isEqualTo("15000000");
        assertThat(params.get("vnp_TxnRef")).isEqualTo(checkout.providerRef());
        assertThat(params.get("vnp_IpAddr")).isEqualTo("10.0.0.7");
        assertThat(params.get("vnp_ReturnUrl")).isEqualTo("https://rentgg.test/return/vnpay");
        assertThat(params.get("vnp_CurrCode")).isEqualTo("VND");
    }
}
