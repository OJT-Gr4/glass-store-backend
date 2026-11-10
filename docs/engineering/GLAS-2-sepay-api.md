# GLAS-2 — SePay Payment Gateway sandbox API

Backend implementation for S3-02. Scope: ONLINE orders using `VIETQR` (SePay BANK_TRANSFER).
POS cash/manual transfer keep their existing flow; VNPAY is not mapped to SePay.
The paired customer-web implementation offers SePay at checkout, submits the signed
form, and restores payment status at `/orders/{orderCode}` after return/reload.

## Configuration

Keep these values in an ignored local `.env` or deployment secret store:

```dotenv
PAYMENT_PROVIDER=sepay
SEPAY_MERCHANT_ID=
SEPAY_SECRET_KEY=
SEPAY_IPN_SECRET=
SEPAY_RETURN_BASE_URL=http://localhost:5184
```

Use sandbox credentials. `SEPAY_IPN_SECRET` is a separate random key of at least
32 characters, not the merchant secret. Never put either secret in frontend code,
Git, API responses, screenshots or logs. The service's existing `spring.config.import`
loads the backend `.env`; environment variables can also be supplied by deployment.

Apply `docker/mysql/migrations/20261109-sepay-payment.sql` to add the nullable
`payments.provider_order_id` column before starting with schema validation.
Existing rows and existing payment uniqueness constraints are preserved.

In the SePay sandbox merchant dashboard configure IPN authentication type
`SECRET_KEY`, with the same independent IPN key, and this public HTTPS URL:

```text
https://<backend-public-host>/api/payments/sepay/ipn
```

The API gateway forwards that path to order-pos-service. A localhost URL is not
reachable by SePay. Local HTTP is allowed only for browser return URLs on
`localhost` or `127.0.0.1`; public return URLs must use HTTPS.

## API contract

1. Create the online order with existing `POST /api/orders`, authenticated JWT,
   `Idempotency-Key`, `channel: ONLINE`, `paymentMethod: VIETQR`. Existing server
   pricing calculates the final amount; the order/payment remain pending.
2. `POST /api/payments/order/{orderId}/initiate`, authenticated owner or sales/admin.
   No client amount, merchant credentials or redirect URL is accepted.
   Response `data` includes:

   ```json
   {
     "provider": "SEPAY",
     "externalTransactionId": null,
     "paymentUrl": "https://pay-sandbox.sepay.vn/v1/checkout/init",
     "qrContent": null,
     "requiresCustomerAction": true,
     "httpMethod": "POST",
     "formFields": {
       "merchant": "<merchant id>",
       "operation": "PURCHASE",
       "payment_method": "BANK_TRANSFER",
       "order_invoice_number": "<stored orderCode>",
       "order_amount": "<stored finalAmount, whole VND>",
       "currency": "VND",
       "order_description": "Thanh toan don hang <stored orderCode>",
       "success_url": "<return base>/orders/<orderCode>?payment=success",
       "error_url": "<return base>/orders/<orderCode>?payment=error",
       "cancel_url": "<return base>/orders/<orderCode>?payment=cancel",
       "signature": "<Base64 HMAC-SHA256>"
     }
   }
   ```

   The FE must submit an HTML form with method POST to `paymentUrl`, adding hidden
   fields in the returned insertion order. Do not open the URL as a GET, sort or
   edit the fields. SePay signing uses comma-joined `key=value` entries in the SDK's
   insertion order (not sorted/URL-encoded). The signature is generated server-side.
   Repeated initiation reuses the pending local payment under the order lock.

3. SePay calls `POST /api/payments/sepay/ipn` without application JWT, with header
   `X-Secret-Key`. This provider documents a shared IPN secret, not a body HMAC.
   The adapter compares it in constant time before parsing. Required payload shape:

   ```json
   {
     "timestamp": 1794182400,
     "notification_type": "ORDER_PAID",
     "order": {
       "id": "<provider order id>",
       "order_invoice_number": "<stored orderCode>",
       "order_status": "CAPTURED",
       "order_currency": "VND",
       "order_amount": "10000.00"
     },
     "transaction": {
       "id": "<unique provider transaction id>",
       "transaction_id": "<bank reference>",
       "transaction_type": "PAYMENT",
       "transaction_status": "APPROVED",
       "transaction_currency": "VND",
       "payment_method": "BANK_TRANSFER",
       "transaction_amount": "10000"
     }
   }
   ```

   A previously initiated SePay payment must exist. Both provider amounts must
   equal the saved obligation, including on duplicate callbacks. Only then call
   the existing `markPaid` flow, with the exact payment ID. The transaction stores
   provider order ID, provider transaction ID, bank reference and confirmation audit.
   Success returns HTTP 200 `{"success":true,"replayed":false}` after commit;
   a duplicate returns `replayed:true` without another payment/stock commit.
4. Read stored status via existing `GET /api/orders/{id}` and
   `GET /api/payments/order/{id}` with the owner JWT. Return query `payment=success`
   is navigation only: it never marks an order paid. Frontend return/pending UI is
   wired to those stored statuses in customer-web. It polls for at most two minutes,
   pauses while hidden, and provides a manual refresh. No UI assumes a timeout means
   payment failure or infers success from return query parameters.
5. `GET /api/payments/sepay/configuration`, authenticated, reports environment,
   configuration presence (`CONFIGURED` or `BLOCKED`) and missing variable names.
   `CONFIGURED` means presence/format checks only, not merchant approval or UAT passed.

## Failure behavior and verification

| Case | Expected |
|---|---|
| Missing merchant/secret/return configuration | Initiate 503; remain pending; configuration BLOCKED |
| Missing/incorrect IPN key | 401; no payment change |
| Malformed payload / unsupported state or currency | 400; no payment change |
| Order amount differs from callback / pending payment | 409; no payment change |
| Unknown order / no initiated SePay payment | 404; no unsolicited payment created |
| Concurrent/repeated valid IPN | One successful payment; one idempotent stock commit |
| Same transaction replay with altered amount or references | 409; original payment unchanged |
| Late success for cancelled order / second successful transaction | 409; no reopening; needs manual reconciliation |
| No IPN / timeout / user returns from checkout | Order remains pending; no inferred success |
| Customer accesses another customer's payment | 403 |

Existing durable inventory tasks retain retry/reconciliation behavior when stock
services fail after money is confirmed. This increment does not implement automatic
provider refunds, `TRANSACTION_VOID` handling, or a reconciliation dashboard (GLAS-9).
Late/unsupported notifications return a non-success response; operations must review
them against the provider dashboard rather than assume money was refunded.

Tests: `SePayGatewayTest` checks a fixed signature vector calculated independently
with Node crypto, invalid keys/payloads/statuses/amounts and configuration guards.
`SePayApiIntegrationTest` runs real Spring/JPA transactions and MockMvc on isolated
H2 with the real adapter; only catalog/lab/warranty dependencies are mocked. It
checks owner access, unsigned rejection, pending state, callback races, DB readback,
stock commit deduplication and cancellation. Gateway security tests check IPN routing.
These tests do not contact SePay and are not sandbox UAT evidence.

Actual sandbox end-to-end acceptance is **BLOCKED** until merchant credentials and
public HTTPS IPN are configured, and a real sandbox payment/callback is recorded.
Record expected/actual API + DB results for that run before marking GLAS-2 Done.

### Local verification result — 2026-11-09, Java 21

- Full initial run: 131 order tests + 4 gateway tests passed, zero failures/errors.
- Final focused run after adding two additional cases: 18 adapter tests, 6 SePay
  API/DB integration tests, 6 payment-service tests and 4 gateway security tests
  passed, zero failures/errors.
- Actual matched expected: four concurrent authenticated callbacks returned the
  same payment ID; DB readback showed one SUCCESS payment, PAID order, stored
  provider/bank references and SYNCED stock task; catalog commit was called once.
- Actual matched expected: forged IPN 401, unsolicited payment 404, cancelled
  order 409, changed replay amount/reference 409, reassigned transaction 400,
  non-owner 403; missing merchant reported BLOCKED and kept payment PENDING.
- Isolated H2 only; no SePay network call and no production/MySQL order mutation.

Final integrated verification: **134 order tests + 4 gateway tests** passed. The new
inventory-outage test confirms a SUCCESS payment survives a missing stock acknowledgement,
the persisted stock task remains pending/requires reconciliation, and a duplicate IPN
does not enqueue another commit. Customer-web has **27 passing unit tests** and a
successful production build. Browser checks cover create failure/cart restoration,
retry-key reuse after reload, initiation failure/same-order retry, POST field submission,
return `success` while still pending, confirmed payment and cancelled order, and mobile
layout. Browser happy-path responses are isolated fixtures, **not provider UAT**.

Reproduce using Maven with Java 21 and explicit H2 overrides:

```powershell
mvn -pl order-pos-service,api-gateway -am `
  '-Dspring.datasource.url=jdbc:h2:mem:sepay-suite;MODE=MySQL;DB_CLOSE_DELAY=-1' `
  '-Dspring.datasource.driver-class-name=org.h2.Driver' `
  '-Dspring.datasource.username=sa' '-Dspring.datasource.password=' `
  '-Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect' `
  '-Dspring.jpa.hibernate.ddl-auto=create-drop' test
```

## Official references (checked 2026-11-09)

- https://developer.sepay.vn/vi/cong-thanh-toan/sandbox
- https://developer.sepay.vn/vi/cong-thanh-toan/IPN
- https://developer.sepay.vn/vi/cong-thanh-toan/sdk/nodejs
- https://github.com/sepayvn/sepay-pg-node/blob/main/src/checkout.ts
