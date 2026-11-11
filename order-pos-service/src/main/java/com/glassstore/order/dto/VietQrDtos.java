package com.glassstore.order.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

public class VietQrDtos {

    /**
     * Response from Step 1 (Partner API Get Token) and Step 3 (VietQR API Get Token).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenResponse {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("token_type")
        private String tokenType;

        @JsonProperty("expires_in")
        private Long expiresIn;
    }

    /**
     * Request body for Step 4 (VietQR Generate QR Code API: /vqr/api/qr/generate-customer).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerateCustomerRequest {
        @JsonProperty("bankCode")
        private String bankCode;

        @JsonProperty("bankAccount")
        private String bankAccount;

        @JsonProperty("userBankName")
        private String userBankName;

        @JsonProperty("content")
        private String content;

        @JsonProperty("qrType")
        private Integer qrType; // 0 for dynamic QR

        @JsonProperty("amount")
        private BigDecimal amount;

        @JsonProperty("orderId")
        private String orderId;

        @JsonProperty("transType")
        private String transType; // "C" for credit
    }

    /**
     * Response body from Step 4.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerateCustomerResponse {
        private String bankCode;
        private String bankName;
        private String bankAccount;
        private String userBankName;
        private String amount;
        private String content;
        private String qrCode;
        private String qrDataURL;
        private String imgId;
        private String existing;
        private String transactionId;
    }

    /**
     * Request body for Step 2 (Transaction Sync: POST /bank/api/transaction-sync).
     * VietQR posts balance fluctuations here.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionSyncRequest {
        @JsonProperty("bankAccount")
        @JsonAlias({"bankaccount", "bank_account", "accountNo"})
        private String bankAccount;

        @JsonProperty("amount")
        private BigDecimal amount;

        @JsonProperty("transType")
        @JsonAlias({"transtype", "trans_type"})
        private String transType;

        @JsonProperty("content")
        private String content;

        @JsonProperty("transactionid")
        @JsonAlias({"transactionId", "transaction_id", "tranId"})
        private String transactionId;

        @JsonProperty("transactiontime")
        @JsonAlias({"transactionTime", "transaction_time"})
        private Long transactionTime;

        @JsonProperty("referencenumber")
        @JsonAlias({"referenceNumber", "reference_number", "refNo"})
        private String referenceNumber;

        @JsonProperty("orderId")
        @JsonAlias({"orderid", "order_id"})
        private String orderId;

        @JsonProperty("terminalCode")
        @JsonAlias({"terminalcode"})
        private String terminalCode;

        @JsonProperty("sign")
        private String sign;
    }

    /**
     * Response body for Step 2 per VietQR specification:
     * {
     *   "error": false,
     *   "errorReason": "",
     *   "toastMessage": "Success",
     *   "object": {
     *     "reftransactionid": "..."
     *   }
     * }
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransactionSyncResponse {
        private boolean error;
        private String errorReason;
        private String toastMessage;
        private SyncObject object;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class SyncObject {
            private String reftransactionid;
        }

        public static TransactionSyncResponse success(String transactionId) {
            return TransactionSyncResponse.builder()
                    .error(false)
                    .errorReason("")
                    .toastMessage("Success")
                    .object(SyncObject.builder().reftransactionid(transactionId).build())
                    .build();
        }

        public static TransactionSyncResponse failure(String reason) {
            return TransactionSyncResponse.builder()
                    .error(true)
                    .errorReason(reason)
                    .toastMessage(reason)
                    .object(null)
                    .build();
        }
    }

    /**
     * Request body for Step 5 (Calling VietQR's Dev test callback API: /vqr/bank/api/test/transaction-callback).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TestCallbackRequest {
        @JsonProperty("bankAccount")
        private String bankAccount;

        @JsonProperty("content")
        private String content;

        @JsonProperty("amount")
        private BigDecimal amount;

        @JsonProperty("transType")
        private String transType;

        @JsonProperty("bankCode")
        private String bankCode;
    }
}
