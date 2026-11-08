package com.glassstore.gateway.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

@Configuration @PropertySource("classpath:glassstore-security.properties")
public class GatewaySecurityConfig {
    @Bean ReactiveJwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        var decoder = NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> access = jwt -> "ACCESS".equals(jwt.getClaimAsString("type"))
                && jwt.getSubject() != null && jwt.getSubject().matches("[0-9]+") && jwt.getExpiresAt() != null
                && jwt.getClaimAsStringList("roles") != null && !jwt.getClaimAsStringList("roles").contains("ROLE_SERVICE")
            ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(),access));
        return decoder;
    }
    @Bean SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        var roles=new JwtGrantedAuthoritiesConverter(); roles.setAuthoritiesClaimName("roles"); roles.setAuthorityPrefix("");
        var converter=new JwtAuthenticationConverter(); converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
            .authorizeExchange(a -> a.pathMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .pathMatchers("/internal/**","/api/variants/**","/api/lab-jobs/from-order","/api/lab-jobs/by-order/**","/api/warranties/activate").denyAll()
                .pathMatchers("/api/auth/me","/api/v1/auth/me").authenticated()
                .pathMatchers("/api/auth/**","/api/v1/auth/**","/api/payments/callback").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/locations").permitAll()
                .pathMatchers(HttpMethod.GET,"/api/products","/api/products/**","/api/categories/**").permitAll()
                .pathMatchers("/api/catalog/admin/**").hasRole("ADMIN")
                .pathMatchers(HttpMethod.POST,"/api/users/staff","/api/v1/users/staff").hasRole("ADMIN")
                .pathMatchers("/api/**").authenticated().anyExchange().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(converter))))
            .build();
    }
}
