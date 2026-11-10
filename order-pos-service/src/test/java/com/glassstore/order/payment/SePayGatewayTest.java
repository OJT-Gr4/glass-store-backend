package com.glassstore.order.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class SePayGatewayTest {
    static final String IPN_SECRET = "unit-test-ipn-secret-independent-123456";
    private final ObjectMapper json = new ObjectMapper();
    private SePayProperties properties;
    private SePayGateway gateway;

    @BeforeEach
    void setup() {
        properties = new SePayProperties();
        properties.setMerchantId("TEST_MERCHANT");
        properties.setSecretKey("sandbox-unit-test-only");
        properties.setIpnSecret(IPN_SECRET);
        properties.setReturnBaseUrl("http://localhost:5184");
        gateway = new SePayGateway(properties, json);
    }

    @Test
    void checkoutMatchesIndependentNodeCryptoVectorAndPreservesFieldOrder() {
        var result = gateway.initiate(new PaymentGateway.InitiationRequest("QA-001", new BigDecimal("10000.00"), "ignored"));
        assertThat(result.httpMethod()).isEqualTo("POST");
        assertThat(result.paymentUrl()).isEqualTo(SePayGateway.CHECKOUT_URL);
        assertThat(result.formFields().keySet()).containsExactly("merchant", "operation", "payment_method",
                "order_invoice_number", "order_amount", "currency", "order_description",
                "success_url", "error_url", "cancel_url", "signature");
        // Computed independently using Node crypto and the official SDK's comma-joined insertion order.
        assertThat(result.formFields().get("signature")).isEqualTo("IFTDOdiTuiUwQPLAMkutIh7AMkQiJfducZ7Q+AiKR4c=");
        assertThat(result.formFields().values()).doesNotContain(properties.getSecretKey(), IPN_SECRET);
        var changed = gateway.initiate(new PaymentGateway.InitiationRequest("QA-001", new BigDecimal("10001"), "ignored"));
        assertThat(changed.formFields().get("signature")).isNotEqualTo(result.formFields().get("signature"));
    }

    @Test
    void missingMerchantAndPlaceholdersAreBlocked() {
        properties.setMerchantId("");
        assertThatThrownBy(() -> gateway.initiate(new PaymentGateway.InitiationRequest("QA", BigDecimal.TEN, "")))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
        properties.setMerchantId("TEST_MERCHANT");
        properties.setSecretKey("YOUR_SECRET_KEY");
        assertThat(gateway.missingConfiguration()).contains("SEPAY_SECRET_KEY");
        properties.setSecretKey("spsk_live_test");
        assertThat(gateway.missingConfiguration()).doesNotContain("SEPAY_SECRET_KEY");
    }

    @Test
    void liveSecretKeySelectsLiveCheckoutUrl() {
        properties.setSecretKey("spsk_live_12345");
        var result = gateway.initiate(new PaymentGateway.InitiationRequest("QA-001", new BigDecimal("10000"), "ignored"));
        assertThat(result.paymentUrl()).isEqualTo("https://pay.sepay.vn/v1/checkout/init");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/?next=foo", "https://user:password@example.com", "http://public.example.com", "javascript:alert(1)"})
    void rejectsUnsafeReturnBase(String base) {
        properties.setReturnBaseUrl(base);
        assertThat(gateway.missingConfiguration()).contains("SEPAY_RETURN_BASE_URL");
    }

    @Test
    void authenticatedIpnRetainsAllProviderIdentifiers() {
        var verified = gateway.verifyCallback(payload().toString(), IPN_SECRET);
        assertThat(verified.valid()).isTrue();
        assertThat(verified.success()).isTrue();
        assertThat(verified.amount()).isEqualByComparingTo("10000");
        assertThat(verified.externalTransactionId()).isEqualTo("txn-uuid");
        assertThat(verified.providerOrderId()).isEqualTo("provider-order-uuid");
        assertThat(verified.paymentReference()).isEqualTo("bank-reference");
    }

    @Test
    void rejectsMissingOrInvalidIpnKeyBeforeParsingPayload() {
        for (String key : new String[]{null, "wrong"}) {
            assertThatThrownBy(() -> gateway.verifyCallback("not-json", key))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(error -> ((ResponseStatusException) error).getStatusCode().value()).isEqualTo(401);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "broken-json"})
    void rejectsMalformedPayload(String body) {
        assertThatThrownBy(() -> gateway.verifyCallback(body, IPN_SECRET)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "1000000000000", "NaN"})
    void rejectsInvalidVndAmounts(String amount) {
        ObjectNode body = payload();
        ((ObjectNode) body.get("transaction")).put("transaction_amount", amount);
        assertThatThrownBy(() -> gateway.verifyCallback(body.toString(), IPN_SECRET)).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsMismatchStatusCurrencyAndMissingTransactionId() {
        ObjectNode body = payload();
        ((ObjectNode) body.get("transaction")).put("transaction_amount", "9999");
        assertThatThrownBy(() -> gateway.verifyCallback(body.toString(), IPN_SECRET))
                .isInstanceOf(ApiException.class).extracting(error -> ((ApiException) error).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        for (String field : new String[]{"transaction_status", "transaction_currency", "transaction_type", "payment_method", "id", "transaction_id"}) {
            ObjectNode invalid = payload();
            ((ObjectNode) invalid.get("transaction")).put(field, "");
            assertThatThrownBy(() -> gateway.verifyCallback(invalid.toString(), IPN_SECRET)).isInstanceOf(ApiException.class);
        }
        String invalidTimestamp = payload().put("timestamp", 1.5).toString();
        assertThatThrownBy(() -> gateway.verifyCallback(invalidTimestamp, IPN_SECRET)).isInstanceOf(ApiException.class);
    }

    private ObjectNode payload() {
        ObjectNode body = json.createObjectNode().put("timestamp", 1794182400L).put("notification_type", "ORDER_PAID");
        body.putObject("order").put("id", "provider-order-uuid").put("order_invoice_number", "QA-001")
                .put("order_status", "CAPTURED").put("order_currency", "VND").put("order_amount", "10000.00");
        body.putObject("transaction").put("id", "txn-uuid").put("transaction_id", "bank-reference")
                .put("transaction_type", "PAYMENT").put("transaction_status", "APPROVED")
                .put("transaction_currency", "VND").put("payment_method", "BANK_TRANSFER").put("transaction_amount", "10000");
        return body;
    }
}
