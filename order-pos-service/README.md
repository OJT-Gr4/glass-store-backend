# GlassStore Order/POS Service

Order, POS, cart, pricing, payment, inventory/Lab dispatch and warranty activation service. Java 21; default listener 8083; schema db_order on the shared MySQL server at 127.0.0.1:3306.

Use [the backend setup guide](../README.md) and ../.env.example. Do not maintain a separate port/password setup for this module. application-local.yml honors the common DB_HOST/DB_PORT/DB_USERNAME/DB_PASSWORD variables; ORDER_DB_URL and ORDER_DB_USERNAME/ORDER_DB_PASSWORD are deliberate per-service overrides only.

From backend, START_GLASSSTORE.bat launches all services and both frontends. CHECK_GLASSSTORE.bat checks readiness; STOP_GLASSSTORE.bat stops only launcher-owned application processes. Runtime logs are in backend/.runtime.

Canonical SQL demo data is imported only on fresh schemas. Legacy Java sample initializers are disabled by default. H2 is used for isolated tests; do not use a create-drop test profile against MySQL development data.

Online checkout creates a pending payment. A QR code or frontend return page is not proof of payment success. Keep payment/provider status, stock outbox status and Lab status distinct; retry and cancellation must not duplicate side effects. Payment-provider credentials and policy approvals are separate from the local environment setup.