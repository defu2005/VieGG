package com.rentgg.payment.application;

import com.rentgg.shared.error.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * One payment provider (PSP). Several can be active at once; {@link PaymentGateways} picks one per top-up.
 * Implementations live in {@code payment.infrastructure} and never touch the domain: they only build checkouts,
 * check signatures and translate the provider's callback into a {@link WebhookPayload}.
 */
public interface PaymentGateway {

    /** Upper-case code stored in {@code payment_intents.provider} and used in callback URLs (lower case). */
    String provider();

    /** Name shown to users. */
    String displayName();

    /** Credentials are present: the provider is offered to users. */
    boolean isConfigured();

    /** Points at the provider's test environment. */
    boolean sandbox();

    /** Calls the provider and returns where to send the payer. Throws {@link ProviderException} on refusal. */
    Checkout createCheckout(CheckoutRequest req);

    /** Server-to-server notification (IPN/webhook). Throws {@link IllegalArgumentException} if malformed. */
    Verified verifyCallback(CallbackRequest req);

    /**
     * Browser redirect back from the provider. Either checks the signed redirect parameters or asks the provider
     * for the payment status. Lets local dev finish a sandbox payment even though the provider cannot reach
     * localhost with its IPN.
     */
    default Verified verifyReturn(CallbackRequest req) {
        return Verified.INVALID;
    }

    /** What to answer the provider's callback for this outcome. */
    default Ack ack(Outcome outcome) {
        return switch (outcome) {
            case INVALID_SIGNATURE ->
                    throw new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "Chữ ký webhook không hợp lệ.");
            case MALFORMED -> throw ApiException.badRequest("MALFORMED_REQUEST", "Webhook không đúng định dạng.");
            default -> new Ack(200, Map.of("status", "received"));
        };
    }

    record CheckoutRequest(UUID intentId, long amount, String description, String returnUrl, String notifyUrl,
                           String clientIp, Instant expiresAt) {}

    record Checkout(String providerRef, String checkoutUrl) {}

    /** {@code params}: query (or form) parameters; {@code headers}: lower-case names. */
    record CallbackRequest(String method, Map<String, String> params, Map<String, String> headers, byte[] body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String param(String name) {
            return params.get(name);
        }
    }

    /** {@code status}: SUCCEEDED, FAILED or PENDING (nothing to apply yet). */
    record WebhookPayload(String providerRef, String status, long amount) {}

    record Verified(boolean signatureValid, WebhookPayload payload) {
        public static final Verified INVALID = new Verified(false, null);

        public static Verified of(WebhookPayload payload) {
            return new Verified(true, payload);
        }
    }

    enum Outcome { OK, DUPLICATE, FAILED, PENDING, UNKNOWN_REF, AMOUNT_MISMATCH, INVALID_SIGNATURE, MALFORMED }

    record Ack(int status, Object body) {}

    /** The provider refused or could not be reached. */
    class ProviderException extends RuntimeException {
        public ProviderException(String message) {
            super(message);
        }

        public ProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
