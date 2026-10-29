package com.rentgg.payment.api;

import com.rentgg.config.RentGgProperties;
import com.rentgg.payment.application.PaymentGateway;
import com.rentgg.payment.application.PaymentService;
import com.rentgg.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private final PaymentService payments;
    private final RentGgProperties props;

    public PaymentController(PaymentService payments, RentGgProperties props) {
        this.payments = payments;
        this.props = props;
    }

    /** Payment providers the user can top up with, the default one first. */
    @GetMapping("/providers")
    public List<PaymentService.ProviderView> providers() {
        return payments.providers();
    }

    /**
     * Provider callback (IPN/webhook). Public: each provider signs its own way (FAKE: X-Signature header; VNPay:
     * signed query on a GET; MoMo/ZaloPay/PayOS: signed JSON body). The body is read raw, before JSON parsing.
     */
    @RequestMapping(value = "/webhook/{provider}", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<Object> webhook(@PathVariable String provider, HttpServletRequest request,
                                          @RequestBody(required = false) byte[] rawBody) {
        PaymentGateway.Ack ack = payments.handleWebhook(provider, callback(request, rawBody));
        return ResponseEntity.status(ack.status()).body(ack.body());
    }

    /** Payer's browser back from the provider's page → applies the verified result, then the wallet page. */
    @GetMapping("/return/{provider}")
    public ResponseEntity<Void> paymentReturn(@PathVariable String provider, HttpServletRequest request) {
        PaymentService.ReturnResult r = payments.handleReturn(provider, callback(request, null));
        var url = UriComponentsBuilder.fromHttpUrl(props.payment().frontendBaseUrl()).path("/wallet")
                .queryParam("paymentResult", r.status());
        if (r.intentId() != null) {
            url.queryParam("payment", r.intentId());
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, url.build().toUriString()).build();
    }

    static PaymentGateway.CallbackRequest callback(HttpServletRequest request, byte[] rawBody) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> {
            if (v.length > 0) {
                params.put(k, v[0]);
            }
        });
        Map<String, String> headers = new HashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return new PaymentGateway.CallbackRequest(request.getMethod(), params, headers,
                rawBody == null ? new byte[0] : rawBody);
    }

    /** Dev/test only: simulates a successful PSP webhook for the caller's own intent. */
    @RestController
    @RequestMapping("/api/v1/dev/payments")
    @Profile({"dev", "test"})
    public static class DevPaymentController {

        private final PaymentService payments;

        public DevPaymentController(PaymentService payments) {
            this.payments = payments;
        }

        @PostMapping("/{intentId}/simulate-success")
        public Map<String, String> simulate(@PathVariable UUID intentId) {
            payments.simulateSuccess(intentId, CurrentUser.id());
            return Map.of("status", "SUCCEEDED");
        }
    }
}
