package com.glassstore.catalog.config;
import com.glassstore.common.security.ServiceJwtConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
@Configuration @Import(ServiceJwtConfiguration.class)
public class InventorySecurityConfig {
    @Bean SecurityFilterChain inventorySecurityFilterChain(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll().requestMatchers("/error").permitAll()
                .requestMatchers("/api/catalog/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/inventory/suppliers").hasAnyRole("ADMIN","LAB","LAB_TECH")
                .requestMatchers("/api/inventory/suppliers/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/inventory/vouchers/*/approve", "/api/inventory/vouchers/*/reject").hasRole("ADMIN")
                .requestMatchers("/api/inventory/vouchers/**", "/api/inventory/defects/**").hasAnyRole("ADMIN","LAB","LAB_TECH")
                .requestMatchers(HttpMethod.GET, "/api/locations").permitAll()
                .requestMatchers(HttpMethod.GET,"/api/products","/api/products/**","/api/categories/**").permitAll()
                .requestMatchers("/internal/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET,"/api/variants/**").hasAnyRole("SERVICE","ADMIN","SALES_STAFF","LAB_TECH","LAB")
                .requestMatchers("/api/variants/adjust").hasAnyRole("ADMIN","LAB_TECH","LAB")
                .requestMatchers("/api/variants/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET,"/api/inventory","/api/inventory/locations").hasAnyRole("ADMIN","LAB","LAB_TECH","SALES_STAFF")
                .requestMatchers("/api/inventory/**").hasAnyRole("ADMIN","LAB","LAB_TECH")
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
}
