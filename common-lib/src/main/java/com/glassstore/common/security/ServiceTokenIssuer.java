package com.glassstore.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

public final class ServiceTokenIssuer {
    private ServiceTokenIssuer() {}
    public static String issue(String secret, String service) {
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secret.getBytes(StandardCharsets.UTF_8)));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject("service:" + service).issuedAt(now).expiresAt(now.plusSeconds(60))
                .claim("type", "SERVICE").claim("roles", List.of("ROLE_SERVICE")).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
