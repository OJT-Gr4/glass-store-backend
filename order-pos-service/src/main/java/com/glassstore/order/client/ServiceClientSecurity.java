package com.glassstore.order.client;
import com.glassstore.common.security.ServiceTokenIssuer;
import feign.RequestInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
/** Opt-in Feign configuration: no propagation of customer credentials to internal contracts. */
public class ServiceClientSecurity {
    @Bean RequestInterceptor serviceAuthorization(@Value("${jwt.secret}") String secret,
            @Value("${spring.application.name}") String service) {
        return template -> template.header("Authorization", "Bearer " + ServiceTokenIssuer.issue(secret, service));
    }
}
