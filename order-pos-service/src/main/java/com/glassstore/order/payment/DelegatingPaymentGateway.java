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
        return delegate().initiate(request);
    }

    @Override
    public VerificationResult verifyCallback(String rawPayload, String signature) {
        PaymentGateway primary = delegate();
        if (primary instanceof SePayGateway) {
            return primary.verifyCallback(rawPayload, signature);
        }
        SePayGateway sg = sePayGateway.getIfAvailable();
        if (sg != null && (rawPayload.contains("order_invoice_number") || rawPayload.contains("notification_type"))) {
            return sg.verifyCallback(rawPayload, signature);
        }
        return primary.verifyCallback(rawPayload, signature);
    }

    @Override
    public Optional<VerificationResult> queryStatus(String orderCode) {
        return delegate().queryStatus(orderCode);
    }
}
