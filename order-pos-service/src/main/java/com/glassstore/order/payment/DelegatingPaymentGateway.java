package com.glassstore.order.payment;

import com.glassstore.common.exception.ApiException;
import com.glassstore.common.exception.ErrorCode;
import com.glassstore.order.domain.PaymentMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Primary
@Slf4j
public class DelegatingPaymentGateway implements PaymentGateway {

    @Value("${payment.provider:sepay}")
    private String configuredProvider;

    private final ObjectProvider<SePayGateway> sePayGateway;
    private final ObjectProvider<ConfigurableSandboxPaymentGateway> sandboxGateway;
    private final ObjectProvider<UnconfiguredPaymentGateway> unconfiguredGateway;

    public DelegatingPaymentGateway(ObjectProvider<SePayGateway> sePayGateway,
                                    ObjectProvider<ConfigurableSandboxPaymentGateway> sandboxGateway,
                                    ObjectProvider<UnconfiguredPaymentGateway> unconfiguredGateway) {
        this.sePayGateway = sePayGateway;
        this.sandboxGateway = sandboxGateway;
        this.unconfiguredGateway = unconfiguredGateway;
    }

    public PaymentGateway delegate(String provider) {
        if ("sepay".equalsIgnoreCase(provider)) {
            SePayGateway sg = sePayGateway.getIfAvailable();
            if (sg != null) return sg;
        }
        if ("vietqr".equalsIgnoreCase(provider) || "sandbox".equalsIgnoreCase(provider) || "vnpay".equalsIgnoreCase(provider)) {
            ConfigurableSandboxPaymentGateway csg = sandboxGateway.getIfAvailable();
            if (csg != null) return csg;
        }
        return delegate();
    }

    public PaymentGateway delegate() {
        if ("sepay".equalsIgnoreCase(configuredProvider)) {
            SePayGateway sg = sePayGateway.getIfAvailable();
            if (sg != null) return sg;
        }
        if ("sandbox".equalsIgnoreCase(configuredProvider) || "vietqr".equalsIgnoreCase(configuredProvider)) {
            ConfigurableSandboxPaymentGateway csg = sandboxGateway.getIfAvailable();
            if (csg != null) return csg;
        }
        SePayGateway sg = sePayGateway.getIfAvailable();
        if (sg != null) return sg;
        ConfigurableSandboxPaymentGateway csg = sandboxGateway.getIfAvailable();
        if (csg != null) return csg;
        UnconfiguredPaymentGateway upg = unconfiguredGateway.getIfAvailable();
        if (upg != null) return upg;
        throw ApiException.of(ErrorCode.PAYMENT_PROVIDER_NOT_CONFIGURED);
    }

    @Override
    public boolean supports(PaymentMethod method) {
        return delegate().supports(method);
    }

    @Override
    public InitiationResult initiate(InitiationRequest request) {
        return delegate(request != null ? request.provider() : null).initiate(request);
    }

    @Override
    public VerificationResult verifyCallback(String rawPayload, String signature) {
        if (rawPayload != null && (rawPayload.contains("order_invoice_number") || rawPayload.contains("notification_type"))) {
            SePayGateway sg = sePayGateway.getIfAvailable();
            if (sg != null) {
                return sg.verifyCallback(rawPayload, signature);
            }
        }
        if (rawPayload != null && (rawPayload.contains("orderCode") || rawPayload.contains("externalTransactionId"))) {
            ConfigurableSandboxPaymentGateway csg = sandboxGateway.getIfAvailable();
            if (csg != null) {
                return csg.verifyCallback(rawPayload, signature);
            }
        }
        return delegate().verifyCallback(rawPayload, signature);
    }

    @Override
    public Optional<VerificationResult> queryStatus(String orderCode) {
        SePayGateway sg = sePayGateway.getIfAvailable();
        if (sg != null) {
            Optional<VerificationResult> res = sg.queryStatus(orderCode);
            if (res.isPresent()) return res;
        }
        ConfigurableSandboxPaymentGateway csg = sandboxGateway.getIfAvailable();
        if (csg != null) {
            return csg.queryStatus(orderCode);
        }
        return Optional.empty();
    }
}
