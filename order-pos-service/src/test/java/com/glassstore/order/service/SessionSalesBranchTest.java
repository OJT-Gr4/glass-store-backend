package com.glassstore.order.service;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
class SessionSalesBranchTest {
    void session(String code) {var builder=Jwt.withTokenValue("qa").header("alg","HS256").subject("1");if(code!=null)builder.claim("branchCode",code);SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(builder.build()));}
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void branchCannotBeChangedByForgingCheckoutPayload() {
        session("BRANCH_01");assertEquals("BRANCH_01",SessionSalesBranch.resolve("BRANCH_01",null).code());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->SessionSalesBranch.resolve("BRANCH_02",null)).getStatusCode().value());
        assertEquals("BRANCH_01",SessionSalesBranch.resolve(null,null).code());
    }
    @Test void legacySessionMustLoginAgainAndLabCannotSellAtPos() {
        session(null);assertEquals(403,assertThrows(ResponseStatusException.class,()->SessionSalesBranch.resolve("BRANCH_01",null)).getStatusCode().value());
        session("LAB");assertThrows(ResponseStatusException.class,()->SessionSalesBranch.resolve("BRANCH_01",null));
    }
}
