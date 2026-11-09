# S3-13 Stock Vouchers, Suppliers, and Defective Inventory

## Scope and behavior

- Stock vouchers are created as `PENDING`; creating a voucher does not change stock.
- An Admin approval applies every line and its inventory movements in one database transaction. A rejected or previously decided voucher cannot be approved.
- `RECEIPT` increases physical and variant stock. Each line can mark part of its received quantity as quarantined.
- `ISSUE` decreases only sellable stock. `QUARANTINE` reclassifies existing sellable stock without changing physical quantity.
- Quarantined stock is stored per location, included in the variant's non-sellable total, and excluded from availability checks, paid-order deductions, and new stock transfers.
- Supplier records require a unique code and name. Deactivation is supported; suppliers are not hard-deleted.
- Quarantined stock can be viewed but this task does not define a release-to-sell action. Warranty-return ingestion remains blocked until its upstream contract is agreed.

## API

All endpoints are served by Catalog Service through the API Gateway and return the standard `ApiResponse` envelope.

| Method | Path | Access | Purpose |
|---|---|---|---|
| `GET` | `/api/inventory/vouchers` | Admin, Lab, Lab Tech | List vouchers |
| `GET` | `/api/inventory/vouchers/{id}` | Admin, Lab, Lab Tech | Read voucher details |
| `POST` | `/api/inventory/vouchers` | Admin, Lab, Lab Tech | Create a pending voucher; requires `Idempotency-Key` |
| `POST` | `/api/inventory/vouchers/{id}/approve` | Admin | Approve and apply stock/movements atomically |
| `POST` | `/api/inventory/vouchers/{id}/reject` | Admin | Reject with a reason |
| `GET` | `/api/inventory/suppliers` | Admin, Lab, Lab Tech | List suppliers |
| `POST` | `/api/inventory/suppliers` | Admin | Create a supplier |
| `PUT` | `/api/inventory/suppliers/{id}` | Admin | Update a supplier |
| `PATCH` | `/api/inventory/suppliers/{id}/active` | Admin | Activate/deactivate a supplier |
| `GET` | `/api/inventory/defects` | Admin, Lab, Lab Tech | List balances with quarantined stock |

Voucher request lines contain `sku`, positive integer `quantity`, and `quarantinedQuantity` (zero for non-receipt vouchers). Types are `RECEIPT`, `ISSUE`, and `QUARANTINE`.

## Database rollout

Review and apply `../../docker/mysql/migrations/20261109-s3-13-stock-vouchers-defects.sql` against the intended `db_catalog` schema before deploying the backend. The migration is additive: it adds a zero-default per-location quarantine quantity, supplier/voucher tables, and the existing movement table if it is absent; it does not rewrite stock quantities, orders, or existing movement history. Existing non-sellable variant totals are not automatically allocated to locations.

Run backend integration tests against their H2 datasource or an isolated test database. Do not execute this migration against a shared or production database as part of local validation.

## Reporting and integration limits

Approved vouchers write `STOCK_IN`, `STOCK_OUT`, `DEFECT_IN`, or `DEFECT_QUARANTINE` rows to the existing inventory movement history with `referenceType=STOCK_VOUCHER`. This makes voucher movements queryable by the existing history API; a new aggregate report screen is not included. Warranty-return ingestion and release of quarantined items are not implemented.
