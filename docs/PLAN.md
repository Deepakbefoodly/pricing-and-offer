# Implementation Plan: Checkout & Rewards Service

Requirements: [`docs/TASK.md`](TASK.md)

## 1. Tech stack

| Part | Choice |
|---|---|
| Backend | Java 17, Spring Boot 3 (web + validation), Maven wrapper (`mvnw`), JUnit 5 |
| Storage | In-memory (`ConcurrentHashMap`) behind repository classes |
| Frontend | React + TypeScript + Vite, React Router, plain `fetch`, Tailwind CSS |
| Tests | Backend JUnit only (the frontend is a demo) |

### Repository layout
```
backend/            Spring Boot Maven project (package: store)
frontend/           Vite React TS app
docs/               TASK.md, PLAN.md, API.md
README.md           setup & run
DECISIONS.md        design decisions (required deliverable)
```
The old offer engine at `src/` is removed. Its patterns are reused in `backend/`: duplicate-rejecting repositories, validating input before it enters a cart, and atomic check-then-update.

### Setup & run
Prerequisites: JDK 17, Node 20+.
```bash
# backend → http://localhost:8080
cd backend && ./mvnw spring-boot:run
cd backend && ./mvnw test

# frontend → http://localhost:5173
cd frontend && npm install && npm run dev
```
Configuration:
- `backend/src/main/resources/application.yml`: `store.rewards.n: 5`, `store.rewards.x: 10`, `store.cors.allowed-origins: http://localhost:5173`
- Frontend: `VITE_API_BASE_URL` defaults to `http://localhost:8080`; override via `frontend/.env` (see `frontend/.env.example`). The dev server is pinned to port 5173.

**CORS:** a servlet-level `CorsFilter`, so error responses carry CORS headers too. It allows the frontend origin and the `Idempotency-Key` request header, and exposes the `Location` and `Idempotent-Replayed` response headers.

## 2. Key decisions

| Topic | Decision |
|---|---|
| Money | `BigDecimal`, scale 2, sent in JSON as **strings** (`"25.00"`) in both directions; a JSON number for a money field → `400`, more than 2 decimals → `400` (never rounded silently). Discount = subtotal × x / 100, rounded HALF_UP to cents and capped at the subtotal, so a total is never negative (0.00 allowed). |
| Price change after add-to-cart | Checkout charges the **current** price. The client sends `expectedSubtotal`; a mismatch → `409 PRICE_CHANGED`. |
| Stock | Not reserved in the cart. Adding more than is available → `409 INSUFFICIENT_STOCK`. Checkout re-checks all lines and rejects the whole order on any shortage. |
| Checkout retries | `Idempotency-Key` header is **required**. Same key + same body → replays the original order (200). Same key + different body → `422`. New key on a checked-out cart → `409 CART_ALREADY_CHECKED_OUT`. Only successful checkouts are recorded. |
| Concurrency | One store-wide read/write lock. Checkout validates everything and calls payment **before** changing any state, so a failure leaves nothing to undo. |
| Payment | `PaymentGateway` interface with a fake that succeeds by default and can be made to fail in tests. |
| Coupons | Global bearer code, single use, no expiry, one per order. Admin generates **one coupon per call** for the oldest unrewarded milestone (`placedOrders ≥ (generated + 1) × n`). Orders that used a coupon still count toward milestones. |
| Report | Computed from orders and coupons (no separate counters), under the read lock, so it reconciles and never changes state. |
| Errors | `{ "code", "message", "details" }` with stable codes (table in §4). |
| Admin edits | Price/stock edits carry the product `version` the admin loaded (optimistic check); a stale version → `409 PRODUCT_MODIFIED`, so an edit never overwrites a concurrent sale. Stock is set as an absolute value. |
| Input strictness | Unknown JSON fields, wrong types (`"5"` for a number) and fractions for integers (`1.5`) → `400` with the field named. |
| Admin | Everything under `/admin/**`. No auth (per spec). |

## 3. Vertical slices
Each slice adds: entity → repository → service → controller → tests → frontend screen, and ends demoable.

### Slice 0: Skeleton
- `backend/`: Spring Boot app, config properties, CORS, error model (`ErrorCode`, `ApiException`, `@RestControllerAdvice`). Old `src/` removed.
- `frontend/`: Vite React TS + Tailwind + Router; layout with nav (Products · Cart · Admin); `api.ts` fetch wrapper that throws typed `{code, message}` errors.
- **Demo:** both apps start; an unknown backend route returns a JSON error body.

### Slice 1: Products
- **Entity:** `Product { id, name, unitPrice, availableQty, version }` (immutable; every change → `version + 1`)
- **Seed:** shirt 25.00 ×100, jeans 49.99 ×50, shoes 89.90 ×30, socks 5.49 ×200, **watch 199.00 ×3 (limited)**, hoodie 39.00 ×0
- **Service:** list, get, admin update (version-checked, under the write lock); `StoreLock` introduced here
- **Tests:** seed data present; update validation; **10 concurrent edits from the same version → exactly one wins**
- **Screens:** Products page (table + "Add to cart", enabled in Slice 2); Admin → Products (edit price and stock, conflict notice + reload on `PRODUCT_MODIFIED`)

`GET /products` → 200
```json
[{ "id": "watch", "name": "Limited Watch", "unitPrice": "199.00", "availableQty": 3, "version": 1 }]
```
`PATCH /admin/products/{id}` *(admin)*: `version` required; `unitPrice` and/or `availableQty`
```json
{ "version": 1, "unitPrice": "27.50", "availableQty": 80 }
```
→ 200 product (version 2) · 404 `PRODUCT_NOT_FOUND` · 400 `VALIDATION_ERROR` · 409 `PRODUCT_MODIFIED` (details: `expectedVersion`, `currentVersion`)

### Slice 2: Carts
- **Entity:** `Cart { id, status: OPEN|CHECKED_OUT, items (insertion-ordered), orderId? }`
- **Service:** create, view (priced at current prices), add / set qty / remove. The cart must be OPEN, the product must exist, and quantity is 1–99 and ≤ available stock.
- **Tests:** invalid product or qty never enters the cart; adding merges quantities; concurrent adds to one cart sum correctly
- **Screen:** Cart page (cart ID kept in localStorage, +/- and remove controls, subtotal, out-of-stock rows highlighted, error toasts)

`POST /carts` → 201
```json
{ "id": "cart_8f2c", "status": "OPEN", "items": [], "subtotal": "0.00", "orderId": null }
```
`GET /carts/{id}` → 200
```json
{
  "id": "cart_8f2c", "status": "OPEN", "orderId": null,
  "items": [{ "productId": "shirt", "name": "Shirt", "unitPrice": "25.00", "quantity": 2,
              "lineTotal": "50.00", "availableQty": 100, "inStock": true }],
  "subtotal": "50.00"
}
```
`POST /carts/{id}/items` `{ "productId": "shirt", "quantity": 2 }` → 200 cart
`PUT /carts/{id}/items/{productId}` `{ "quantity": 3 }` → 200 cart
`DELETE /carts/{id}/items/{productId}` → 200 cart
Errors: 404 `CART_NOT_FOUND` / `PRODUCT_NOT_FOUND` · 400 `VALIDATION_ERROR` · 409 `INSUFFICIENT_STOCK` · 409 `CART_NOT_OPEN`

### Slice 3: Checkout & orders
- **Entity:** `Order { id, orderNumber, cartId, lines[], subtotal, discount, total, coupon?, placedAt }`; `OrderLine { productId, name, unitPrice, quantity, lineTotal }` (a snapshot)
- **Service:** under the write lock: cart OPEN and not empty → price the lines → compare `expectedSubtotal` → check stock → charge payment → **commit** (decrement stock, save the order, mark the cart CHECKED_OUT)
- **Tests:**
  - **20 concurrent checkouts for a watch with stock 3** → exactly 3 orders, stock ends at 0
  - The order is unchanged after a later price change
  - `PRICE_CHANGED`
  - Payment failure leaves stock and cart untouched
- **Screens:** Checkout button on the Cart page (confirm dialog on `PRICE_CHANGED`); Order page

`POST /carts/{id}/checkout` `{ "expectedSubtotal": "50.00" }` → 201
```json
{
  "id": "ord_51ad", "orderNumber": 7, "cartId": "cart_8f2c",
  "lines": [{ "productId": "shirt", "name": "Shirt", "unitPrice": "25.00", "quantity": 2, "lineTotal": "50.00" }],
  "subtotal": "50.00", "coupon": null, "discount": "0.00", "total": "50.00",
  "placedAt": "2026-10-07T10:15:30Z"
}
```
`GET /orders/{id}` → 200 order · 404 `ORDER_NOT_FOUND`
Checkout errors: 409 `CART_NOT_OPEN` · 422 `CART_EMPTY` · 409 `PRICE_CHANGED` (details: expected vs current) · 409 `INSUFFICIENT_STOCK` (details: shortages) · 402 `PAYMENT_FAILED`

### Slice 4: Idempotent retries
- **Entity:** `IdempotencyRecord { key, fingerprint(cartId, couponCode, expectedSubtotal), orderId }`
- **Service:** the key check runs first, inside the checkout lock; the record is saved in the commit step
- **Tests:**
  - **The same key sent by 10 threads at once** → one 201 and nine 200s, one order, stock charged once
  - Same key with a different body → 422
  - A failed attempt can be retried with the same key
- **Screen:** checkout keeps one UUID key per attempt; a "Simulate retry" button shows that the same order came back

`POST /carts/{id}/checkout` + header `Idempotency-Key: 7c9e6679-…`
- First call → 201 order
- Replay → 200 same order + header `Idempotent-Replayed: true`
- Errors: 400 `IDEMPOTENCY_KEY_MISSING` · 422 `IDEMPOTENCY_KEY_REUSED` · 409 `CART_ALREADY_CHECKED_OUT`
  ```json
  { "code": "CART_ALREADY_CHECKED_OUT", "message": "Cart cart_8f2c was already checked out", "details": { "orderId": "ord_51ad" } }
  ```

### Slice 5: Coupons
- **Entity:** `Coupon { code, percentOff, milestoneOrderNumber, status: AVAILABLE|REDEEMED, generatedAt, redeemedByOrderId? }`
- **Service:**
  - Generate: one coupon, oldest milestone, under the write lock.
  - Checkout: validate the coupon before payment and mark it REDEEMED in the commit step. The coupon code is part of the idempotency fingerprint.
- **Tests:**
  - **2 concurrent checkouts with the same coupon** → exactly one succeeds
  - A failed checkout leaves the coupon AVAILABLE
  - Milestones with n=2, including concurrent generate calls → no duplicate coupons
  - Rounding (33.33 at 10% → 3.33); 100% coupon → total 0.00
- **Screens:** coupon field at checkout, discount shown on the Order page; Admin → Coupons ("Generate" button + table)

`POST /admin/coupons` *(admin)* → 201
```json
{ "code": "REWARD-0005-K7QP", "percentOff": 10, "milestoneOrderNumber": 5,
  "status": "AVAILABLE", "generatedAt": "2026-10-07T11:00:00Z", "redeemedByOrderId": null }
```
→ 409 `NO_ELIGIBLE_MILESTONE` (details: `nextMilestone`, `placedOrders`)

`GET /admin/coupons` *(admin)* → 200 coupon list

Checkout with a coupon: `{ "expectedSubtotal": "448.00", "couponCode": "REWARD-0005-K7QP" }` → 201 with `"discount": "44.80", "total": "403.20"`
Errors: 404 `COUPON_NOT_FOUND` · 409 `COUPON_ALREADY_REDEEMED`

### Slice 6: Report
- **Service:** derived from orders and coupons under the read lock
- **Tests:** reconciles with orders and coupons; repeated calls return the same result and change no state; consistent while checkouts run concurrently
- **Screen:** Admin → Report (summary cards + quantity table, Refresh button)

`GET /admin/report` *(admin)* → 200
```json
{
  "totalOrders": 12,
  "quantityByProduct": [{ "productId": "shirt", "name": "Shirt", "quantity": 14 }],
  "grossRevenue": "1934.50", "totalDiscounts": "44.80", "netRevenue": "1889.70",
  "coupons": { "generated": 2, "available": 1, "redeemed": 1 }
}
```

### Slice 7: Docs & hardening
- `README.md` (setup, run, config, time spent)
- `docs/API.md` (all endpoints with curl examples)
- `DECISIONS.md` (all required sections; the **AI-usage section is left for the repo owner to write**)
- Repeat the concurrent tests 20× to check for flakiness.

## 4. Error codes

| Code | HTTP |
|---|---|
| `VALIDATION_ERROR`, `IDEMPOTENCY_KEY_MISSING` | 400 |
| `PAYMENT_FAILED` | 402 |
| `NOT_FOUND` (unknown route), `PRODUCT_NOT_FOUND`, `CART_NOT_FOUND`, `ORDER_NOT_FOUND`, `COUPON_NOT_FOUND` | 404 |
| `METHOD_NOT_ALLOWED` | 405 |
| `PRODUCT_MODIFIED`, `CART_NOT_OPEN`, `CART_ALREADY_CHECKED_OUT`, `INSUFFICIENT_STOCK`, `PRICE_CHANGED`, `COUPON_ALREADY_REDEEMED`, `NO_ELIGIBLE_MILESTONE` | 409 |
| `UNSUPPORTED_MEDIA_TYPE` | 415 |
| `CART_EMPTY`, `IDEMPOTENCY_KEY_REUSED` | 422 |
| `INTERNAL_ERROR` (no internals leaked) | 500 |

## 5. Deferred
Auth; persistent database; multiple instances; cart expiry; cancellations and refunds; coupon expiry and per-customer coupons; frontend tests.

## 6. Commits
One or two commits per slice, in order 0 → 7, authored by the repo owner with no AI attribution. The app is runnable after every slice.
