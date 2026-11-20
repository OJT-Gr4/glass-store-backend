package com.glassstore.order.service;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
public final class SessionSalesBranch {
    private SessionSalesBranch() {}
    public static SalesBranch resolve(String requestedCode,String requestedName) {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication instanceof JwtAuthenticationToken jwt) {
            String code=jwt.getToken().getClaimAsString("branchCode");
            if(code==null || code.isBlank())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Vui lòng đăng nhập lại và chọn chi nhánh trước khi bán hàng POS.");
            SalesBranch locked=SalesBranch.resolve(code,null);
            if(requestedCode!=null && !requestedCode.isBlank() || requestedName!=null && !requestedName.isBlank()) {
                SalesBranch requested=SalesBranch.resolve(requestedCode,requestedName);
                if(!locked.code().equals(requested.code()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Chi nhánh bán hàng đã khóa theo phiên đăng nhập. Đăng xuất và đăng nhập lại để đổi chi nhánh.");
            }
            return locked;
        }
        return SalesBranch.resolve(requestedCode,requestedName);
    }
}
