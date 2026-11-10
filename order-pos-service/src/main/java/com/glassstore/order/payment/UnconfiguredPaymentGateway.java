package com.glassstore.order.payment;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Temporary implementation used while no gateway credentials exist (OQ-05).
 *
 * <p>It deliberately <strong>cannot</strong> report success. Every call raises
 * {@code PAYMENT_PROVIDER_NOT_CONFIGURED}, so the platform fails loudly instead of inventing
 * a paid order. Replace this bean with a real adapter once a provider is selected.</p>
 */
@Component
@ConditionalOnProperty(
        name = "payment.provider", havingValue = "unconfigured", matchIfMissing = true)
@Slf4j
public class UnconfiguredPaymentGateway implements PaymentGateway {

    @Override
    public boolean supports(PaymentMethod method) {
        return method != null && method.requiresGateway();
    }

    @Override
    public InitiationResult initiate(InitiationRequest request) {
        log.warn("Payment gateway not configured; refusing to initiate charge for order {}",
                request.orderCode());
        throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                "Cổng thanh toán trực tuyến chưa được cấu hình. Vui lòng chọn thanh toán tiền mặt tại quầy.");
    }

    @Override
    public VerificationResult verifyCallback(String rawPayload, String signature) {
        log.warn("Payment callback received but no gateway is configured; rejecting");
        throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED,
                "Chưa cấu hình cổng thanh toán nên không thể xác thực giao dịch.");
    }
}
