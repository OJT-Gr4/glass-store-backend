package com.glassstore.order.payment;

import com.glassstore.order.domain.PaymentMethod;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Abstraction over a real payment provider (FR-PAY-01/02/06/07).
 *
 * <p>Order and payment state never change because the client said so — only a verified
 * result from an implementation of this interface may mark money as received (BR-16).</p>
 */
public interface PaymentGateway {

    boolean supports(PaymentMethod method);

    /**
     * Starts a charge.
     *
     * @throws com.glassstore.common.exception.ApiException with
     *         {@code PAYMENT_PROVIDER_NOT_CONFIGURED} when no provider is wired up,
     *         so the caller cannot mistake "not configured" for "paid".
     */
    InitiationResult initiate(InitiationRequest request);

    /**
     * Verifies a provider callback.
     *
     * @param rawPayload the original provider payload
     * @param signature the authentication header required by the provider (signature or IPN secret)
     */
    VerificationResult verifyCallback(String rawPayload, String signature);

    /**
     * Actively queries the payment provider for the current settlement status of an order.
     * Allows reliable verification without depending solely on inbound webhooks (e.g. during local development).
     */
    default java.util.Optional<VerificationResult> queryStatus(String orderCode) {
        return java.util.Optional.empty();
    }

    record InitiationRequest(String orderCode, BigDecimal amount, String description, String provider) {
        public InitiationRequest(String orderCode, BigDecimal amount, String description) {
            this(orderCode, amount, description, null);
        }
    }

    record InitiationResult(String provider, String externalTransactionId, String paymentUrl,
                            String qrContent, boolean requiresCustomerAction,
                            String httpMethod, Map<String, String> formFields) {
        public InitiationResult {
            formFields = Collections.unmodifiableMap(new LinkedHashMap<>(formFields));
        }

        public InitiationResult(String provider, String externalTransactionId, String paymentUrl,
                                String qrContent, boolean requiresCustomerAction) {
            this(provider, externalTransactionId, paymentUrl, qrContent, requiresCustomerAction, "GET", Map.of());
        }
    }

    record VerificationResult(boolean valid, String provider, String externalTransactionId,
                              String orderCode, BigDecimal amount, boolean success, String message,
                              String providerOrderId, String paymentReference) {
        public VerificationResult(boolean valid, String provider, String externalTransactionId,
                                  String orderCode, BigDecimal amount, boolean success, String message) {
            this(valid, provider, externalTransactionId, orderCode, amount, success, message, null, null);
        }
    }
}
