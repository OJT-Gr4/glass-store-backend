package com.glassstore.gateway;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.cloud.gateway.enabled=false","spring.cloud.gateway.globalcors.add-to-simple-url-handler-mapping=false",
    "jwt.secret=day3-gateway-test-key-01234567890123456789012345"})
@AutoConfigureWebTestClient @Import(GatewayAccessSecurityTest.Routes.class)
class GatewayAccessSecurityTest {
    @Autowired WebTestClient client;
    @TestConfiguration static class Routes {
        @Bean RouterFunction<ServerResponse> endpoints() {
            return RouterFunctions.route().GET("/api/products",r -> ServerResponse.ok().build())
                .GET("/api/orders",r -> ServerResponse.ok().build())
                .POST("/api/users/staff",r -> ServerResponse.ok().build())
                .POST("/api/lab-jobs/from-order",r -> ServerResponse.ok().build()).build();
        }
    }
    String token(String role,String type,Instant expiration) {
        var encoder=new NimbusJwtEncoder(new ImmutableSecret<>("day3-gateway-test-key-01234567890123456789012345".getBytes(StandardCharsets.UTF_8)));
        var claims=JwtClaimsSet.builder().subject("10").issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiration)
            .claim("type",type).claim("roles",List.of("ROLE_"+role)).build();
        return "Bearer "+encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    }
    @Test void publicCatalogAndAuthenticatedBusinessRoutesAreSeparated() {
        client.get().uri("/api/products").exchange().expectStatus().isOk();
        client.get().uri("/api/orders").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/orders").header("Authorization",token("CUSTOMER","ACCESS",Instant.now().plusSeconds(300)))
            .exchange().expectStatus().isOk();
    }
    @Test void expiredAndRefreshTokensCannotAccessBusinessApis() {
        client.get().uri("/api/orders").header("Authorization",token("ADMIN","ACCESS",Instant.now().minusSeconds(120)))
            .exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/orders").header("Authorization",token("ADMIN","REFRESH",Instant.now().plusSeconds(300)))
            .exchange().expectStatus().isUnauthorized();
    }
    @Test void staffProvisioningRequiresAdminAndInternalPathsStayOffTheEdge() {
        client.post().uri("/api/users/staff").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/users/staff").header("Authorization",token("CUSTOMER","ACCESS",Instant.now().plusSeconds(300)))
            .exchange().expectStatus().isForbidden();
        client.post().uri("/api/users/staff").header("Authorization",token("ADMIN","ACCESS",Instant.now().plusSeconds(300)))
            .exchange().expectStatus().isOk();
        client.post().uri("/api/lab-jobs/from-order").header("Authorization",token("ADMIN","ACCESS",Instant.now().plusSeconds(300)))
            .exchange().expectStatus().isForbidden();
    }
}
