package com.glassstore.order.config;

import com.glassstore.common.security.ServiceJwtConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration @Import(ServiceJwtConfiguration.class)
public class OrderSecurityConfig {
    @Bean SecurityFilterChain orderSecurity(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll()
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.POST,"/api/payments/callback").permitAll()
                .requestMatchers("/internal/warranty-items/**").hasRole("SERVICE")
                .requestMatchers("/internal/location-references/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET,"/api/orders/*/history","/api/orders/*/warranty-activations").hasAnyRole("ADMIN","SALES_STAFF")
                .requestMatchers(HttpMethod.GET,"/api/orders","/api/orders/**").hasAnyRole("ADMIN","SALES_STAFF","CUSTOMER")
                .requestMatchers(HttpMethod.POST,"/api/orders","/api/orders/*/cancel-request","/api/orders/*/return-request").hasAnyRole("ADMIN","SALES_STAFF","CUSTOMER")
                .requestMatchers("/api/orders/**").hasAnyRole("ADMIN","SALES_STAFF")
                .requestMatchers("/api/cart/**","/api/cart","/api/payments/**","/api/vouchers/**","/api/loyalty/**").hasAnyRole("ADMIN","SALES_STAFF","CUSTOMER")
                .requestMatchers("/api/pos/**","/api/promotions/**").hasAnyRole("ADMIN","SALES_STAFF")
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
}
