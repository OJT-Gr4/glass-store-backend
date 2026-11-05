package com.glassstore.lab.config;

import com.glassstore.common.security.ServiceJwtConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration @Import(ServiceJwtConfiguration.class)
public class LabSecurityConfig {
    @Bean SecurityFilterChain labSecurity(HttpSecurity http, JwtAuthenticationConverter converter) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).cors(cors -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS,"/**").permitAll().requestMatchers("/error").permitAll()
                .requestMatchers("/api/lab-jobs/from-order","/api/lab-jobs/by-order/**").hasRole("SERVICE")
                .requestMatchers(HttpMethod.GET,"/api/lab-jobs/**","/api/lab-jobs").hasAnyRole("ADMIN","LAB_TECH","SALES_STAFF")
                .requestMatchers(HttpMethod.POST,"/api/lab-jobs").hasAnyRole("ADMIN","LAB_TECH","SERVICE")
                .requestMatchers("/api/lab-jobs/**").hasAnyRole("ADMIN","LAB_TECH")
                .requestMatchers(HttpMethod.GET,"/api/prescriptions","/api/v1/prescriptions").hasAnyRole("ADMIN","OPTOMETRIST","SALES_STAFF","LAB_TECH")
                .requestMatchers(HttpMethod.GET,"/api/prescriptions/**","/api/v1/prescriptions/**").hasAnyRole("ADMIN","OPTOMETRIST","SALES_STAFF","LAB_TECH","CUSTOMER","SERVICE")
                .requestMatchers("/api/prescriptions/**","/api/prescriptions","/api/v1/prescriptions/**","/api/v1/prescriptions").hasAnyRole("ADMIN","OPTOMETRIST")
                .requestMatchers(HttpMethod.GET,"/api/appointments/**","/api/appointments","/api/v1/appointments/**","/api/v1/appointments").hasAnyRole("ADMIN","OPTOMETRIST","SALES_STAFF","CUSTOMER")
                .requestMatchers(HttpMethod.POST,"/api/appointments","/api/v1/appointments").hasAnyRole("ADMIN","OPTOMETRIST","SALES_STAFF","CUSTOMER")
                .requestMatchers("/api/appointments/**","/api/v1/appointments/**").hasAnyRole("ADMIN","OPTOMETRIST","SALES_STAFF")
                .requestMatchers("/api/warranties/activate").hasAnyRole("ADMIN","SALES_STAFF","SERVICE")
                .requestMatchers(HttpMethod.GET,"/api/warranties/**","/api/warranties").hasAnyRole("ADMIN","SALES_STAFF","CUSTOMER")
                .requestMatchers("/api/warranties/**","/api/warranties").hasAnyRole("ADMIN","SALES_STAFF")
                .requestMatchers("/api/repair-services/**","/api/repair-services").hasAnyRole("ADMIN","SALES_STAFF","LAB_TECH")
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
}
