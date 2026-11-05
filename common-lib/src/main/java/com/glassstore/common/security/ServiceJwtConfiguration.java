package com.glassstore.common.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;

@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:glassstore-security.properties")
public class ServiceJwtConfiguration {
    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        var decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> kind = token -> {
            boolean access = "ACCESS".equals(token.getClaimAsString("type")) && token.getExpiresAt() != null
                    && token.getSubject() != null && token.getSubject().matches("[0-9]+")
                    && token.getClaimAsStringList("roles") != null && !token.getClaimAsStringList("roles").contains("ROLE_SERVICE");
            boolean service = "SERVICE".equals(token.getClaimAsString("type"))
                    && token.getSubject() != null && token.getSubject().startsWith("service:")
                    && token.getClaimAsStringList("roles") != null
                    && token.getClaimAsStringList("roles").equals(java.util.List.of("ROLE_SERVICE"));
            return access || service ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Access token required", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), kind));
        return decoder;
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles"); roles.setAuthorityPrefix("");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}
