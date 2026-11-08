package com.glassstore.identity.security;

import com.glassstore.identity.entity.User;
import com.glassstore.identity.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && jwtTokenProvider.validateToken(jwt)) {
                String tokenType = jwtTokenProvider.getTokenType(jwt);
                if ("ACCESS".equals(tokenType)) {
                    Claims claims = jwtTokenProvider.getClaimsFromToken(jwt);
                    Long userId = Long.parseLong(claims.getSubject());
                    Integer claimedTokenVersion = claims.get("tokenVersion", Integer.class);
                    Optional<User> persistedUser = userRepository.findById(userId);
                    boolean tokenVersionValid = persistedUser
                            .map(u -> "ACTIVE".equalsIgnoreCase(u.getStatus())
                                    && (claimedTokenVersion == null || u.getTokenVersion() == claimedTokenVersion))
                            .orElse(true);

                    if (tokenVersionValid) {
                        String email = claims.get("email", String.class);
                        String username = claims.get("username", String.class);

                        List<String> roles = jwtTokenProvider.getRolesFromToken(jwt);
                        List<SimpleGrantedAuthority> authorities = (roles != null)
                                ? roles.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList())
                                : List.of();

                        UserPrincipal principal = new UserPrincipal(userId, username != null ? username : email, email, authorities);

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal,
                                null,
                                authorities
                        );
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
                }
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

