package com.glassstore.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "vietqr")
public class VietQrProperties {

    private Api api = new Api();
    private Partner partner = new Partner();

    @Data
    public static class Api {
        /**
         * VietQR Open API base URL (Dev Sandbox: https://dev.vietqr.org, Prod: https://api.vietqr.org)
         */
        private String baseUrl = "https://dev.vietqr.org";

        /**
         * Basic Auth credentials provided by VietQR to merchant to call dev.vietqr.org
         */
        private String clientUsername = "customer-vietqrtest-user2468";
        private String clientPassword = "Y3VzdG9tZXItdmlldHFydGVzdC11c2VyMjQ2ODpZM1Z6ZEc5dFpYSXRkbWxsZEhGeWRHVnpkQzExYzJWeU1qUTJPQT09";
    }

    @Data
    public static class Partner {
        /**
         * Username & Password configured by Partner (GlassStore) for VietQR to call our API Get Token
         */
        private String username = "glassstore_pos";
        private String password = "GlassStore@VietQr2026";

        /**
         * Beneficiary banking details for receiving payments
         */
        private String bankCode = "MB";
        private String bankAccount = "0938773607";
        private String userBankName = "CONG TY KINH MAT OPTIVISION";

        /**
         * HMAC secret to sign/verify tokens issued to VietQR for /bank/api/transaction-sync
         */
        private String tokenSecret = "GlassStoreVietQrSecretKey20261111!@#Token300sValidation";
    }
}
