package com.glassstore.order.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Secrets are bound on the server only; never expose this object through an API. */
@Component
@ConfigurationProperties(prefix = "payment.sepay")
@Getter
@Setter
public class SePayProperties {
    private String merchantId = "";
    private String secretKey = "";
    private String ipnSecret = "";
    private String returnBaseUrl = "";
}
