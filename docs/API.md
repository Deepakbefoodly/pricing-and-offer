# API reference

Base URL: `http://localhost:8080`. All request and response bodies are JSON.

## Conventions

**Money** is always a JSON **string** with exactly two decimals, in both directions: `"25.00"`, never `25`
or `25.0`. A JSON number in a money field is rejected (`400`), and so is an amount with more than two
decimals; nothing is rounded silently. Inputs may omit trailing zeros (`"27.5"` is read as `27.50`).
Product prices are 0.01–9,999,999.99; other amounts (such as `expectedSubtotal`) may be up to
999,999,999,999,999.99, so any cart the store lets you build can be checked out.

**Errors** always have the same shape. `code` is stable and meant for programs to branch on; `message` is
for humans; `details` holds machine-readable context and may be empty.

```json
{
  "code": "INSUFFICIENT_STOCK",
  "message": "Not enough stock for 1 item(s) in the cart",
  "details": { "shortages": [{ "productId": "watch", "requested": 3, "available": 1 }] }
}
```

Validation errors name the offending fields in `details.fields`:

```json
{ "code": "VALIDATION_ERROR", "message": "Invalid quantity",
  "details": { "fields": { "quantity": "must be between 1 and 99" } } }
```

**Always JSON.** Every response, including every error, is `application/json`, whatever the `Accept`
header says; a request that only accepts other types gets `406 NOT_ACCEPTABLE`.

**Strict input.** Unknown JSON fields, wrong types (`"5"` for a number, `27.5` for a money string) and
fractions for whole numbers (`1.5` for a quantity) are rejected with `400 VALIDATION_ERROR`.

**Admin endpoints** are everything under `/admin`. Authentication is out of scope for this assignment;
these are the operations a real deployment would restrict to administrators.

**Checkout retries** use the `Idempotency-Key` request header (see [Checkout](#post-cartscartidcheckout)).
Browsers can read the `Location` and `Idempotent-Replayed` response headers (exposed via CORS).

## Error codes

| Code | HTTP | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Malformed body, field or header; `details.fields` says which |
| `IDEMPOTENCY_KEY_MISSING` | 400 | Checkout without an `Idempotency-Key` header |
| `PAYMENT_FAILED` | 402 | Payment declined; nothing was charged or changed |
| `NOT_FOUND` | 404 | No endpoint at this path |
| `PRODUCT_NOT_FOUND` | 404 | Unknown product ID |
| `CART_NOT_FOUND` | 404 | Unknown cart ID |
| `CART_ITEM_NOT_FOUND` | 404 | The product is not in this cart |
| `ORDER_NOT_FOUND` | 404 | Unknown order ID |
| `COUPON_NOT_FOUND` | 404 | Unknown coupon code |
| `METHOD_NOT_ALLOWED` | 405 | Wrong HTTP method for this path |
| `NOT_ACCEPTABLE` | 406 | The `Accept` header excludes `application/json` |
| `PRODUCT_MODIFIED` | 409 | Admin edit based on an outdated product version; reload and retry |
| `CART_NOT_OPEN` | 409 | Cart was checked out and can no longer change |
| `CART_ALREADY_CHECKED_OUT` | 409 | New checkout attempt on a cart that already became an order; `details.orderId` |
| `INSUFFICIENT_STOCK` | 409 | Not enough stock; `details.shortages` lists every short line |
| `PRICE_CHANGED` | 409 | Cart subtotal differs from `expectedSubtotal`; details show both |
| `COUPON_ALREADY_REDEEMED` | 409 | Coupon was already used |
| `NO_ELIGIBLE_MILESTONE` | 409 | No reached-but-unrewarded milestone; details show the next one |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Body is not `application/json` |
| `CART_EMPTY` | 422 | Checkout of a cart with no items |
| `IDEMPOTENCY_KEY_REUSED` | 422 | Same key sent with a different request |
| `RATE_LIMITED` | 429 | Too many unknown coupon codes from this client; `Retry-After` header and `details.retryAfterSeconds` |
| `INTERNAL_ERROR` | 500 | Unexpected failure; no internal details are exposed |

## Resources

**Product**

```json
{ "id": "watch", "name": "Limited Watch", "unitPrice": "199.00", "availableQty": 3, "version": 1 }
```

`version` increases on every change (admin edit or a sale) and is used by admin edits to detect conflicts.

**Cart.** Lines are priced at the **current** catalogue price every time the cart is read.

```json
{
  "id": "cart_8f2c41…", "status": "OPEN", "orderId": null,
  "items": [
    { "productId": "shirt", "name": "Shirt", "unitPrice": "25.00", "quantity": 2,
      "lineTotal": "50.00", "availableQty": 100, "inStock": true }
  ],
  "itemCount": 2, "subtotal": "50.00"
}
```

`status` is `OPEN` or `CHECKED_OUT`; `orderId` is set once checked out. `inStock` is `false` when stock dropped
below the cart quantity after the item was added (checkout would be refused).

**Order.** An immutable snapshot taken at checkout; later product changes never alter it.

```json
{
  "id": "ord_51ad…", "orderNumber": 6, "cartId": "cart_8f2c41…",
  "lines": [{ "productId": "shirt", "name": "Shirt", "unitPrice": "25.00", "quantity": 2, "lineTotal": "50.00" }],
  "subtotal": "50.00",
  "coupon": { "code": "REWARD-0005-K7QPX9MZ2A", "percentOff": 10 },
  "discount": "5.00", "total": "45.00",
  "placedAt": "2026-10-07T10:15:30Z"
}
```

`coupon` is `null` when none was used. Always `total = subtotal − discount` and `0 ≤ discount ≤ subtotal`.

**Coupon**

```json
{
  "code": "REWARD-0005-K7QPX9MZ2A", "percentOff": 10, "milestoneOrderNumber": 5, "status": "AVAILABLE",
  "generatedAt": "2026-10-07T11:00:00Z", "redeemedByOrderId": null, "redeemedAt": null
}
```

`status` is `AVAILABLE` or `REDEEMED`.

## Endpoints

| Method | Path | Admin | Purpose |
|---|---|---|---|
| GET | `/products` | | List the catalogue |
| POST | `/carts` | | Create a cart |
| GET | `/carts/{cartId}` | | View a cart |
| POST | `/carts/{cartId}/items` | | Add a product |
| PUT | `/carts/{cartId}/items/{productId}` | | Set a quantity |
| DELETE | `/carts/{cartId}/items/{productId}` | | Remove a product |
| POST | `/carts/{cartId}/checkout` | | Place the order |
| GET | `/orders/{orderId}` | | Get an order |
| PATCH | `/admin/products/{productId}` | ✓ | Change price and/or stock |
| POST | `/admin/coupons` | ✓ | Generate the next milestone coupon |
| GET | `/admin/coupons` | ✓ | List coupons |
| GET | `/admin/orders` | ✓ | List orders |
| GET | `/admin/report` | ✓ | Sales summary |

---

### `GET /products`

Lists all products, sorted by name. **200** → array of Product.

```bash
curl -s http://localhost:8080/products
```

---

### `POST /carts`

Creates an empty cart. No body. **201** with `Location: /carts/{id}` → Cart.

```bash
curl -s -i -X POST http://localhost:8080/carts
```

### `GET /carts/{cartId}`

**200** → Cart. **404** `CART_NOT_FOUND`.

### `POST /carts/{cartId}/items`

Adds `quantity` units, **merging** with any quantity already in the cart.

```json
{ "productId": "shirt", "quantity": 2 }
```

**200** → Cart.

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `productId` missing; `quantity` missing, not a whole number, outside 1–99, or the merged line would exceed 99 |
| 404 | `CART_NOT_FOUND` / `PRODUCT_NOT_FOUND` | unknown cart or product |
| 409 | `CART_NOT_OPEN` | cart already checked out |
| 409 | `INSUFFICIENT_STOCK` | the resulting quantity is above current stock (stock is not reserved by carts) |

```bash
curl -s -X POST http://localhost:8080/carts/$CART/items \
     -H 'Content-Type: application/json' -d '{"productId":"shirt","quantity":2}'
```

### `PUT /carts/{cartId}/items/{productId}`

Sets the quantity of a product already in the cart.

```json
{ "quantity": 3 }
```

**200** → Cart. Errors as for adding, plus **404** `CART_ITEM_NOT_FOUND` if the product is not in the cart.
Raising a quantity above stock is refused; **lowering is always allowed**, so a line that went out of stock
can be reduced. `quantity: 0` is a validation error; use `DELETE` to remove.

### `DELETE /carts/{cartId}/items/{productId}`

Removes the product. **200** → Cart. **404** `CART_NOT_FOUND` / `CART_ITEM_NOT_FOUND`, **409** `CART_NOT_OPEN`.

---

### `POST /carts/{cartId}/checkout`

Places the order.

Headers:

| Header | Required | Meaning |
|---|---|---|
| `Idempotency-Key` | yes | 1–100 printable ASCII characters, no spaces. Use a new key per checkout attempt and **reuse it when retrying** the same request. |

Body:

```json
{ "expectedSubtotal": "50.00", "couponCode": "REWARD-0005-K7QPX9MZ2A" }
```

| Field | Required | Meaning |
|---|---|---|
| `expectedSubtotal` | yes | The cart subtotal the customer was shown (before any coupon). Checkout refuses to charge if current prices give a different subtotal. |
| `couponCode` | no | Reward coupon. Case-insensitive; surrounding spaces ignored; blank means none. |

Responses:

| Status | Meaning |
|---|---|
| **201** | Order placed. `Location: /orders/{id}`, body is the Order. |
| **200** | **Replay**: this key already placed an order for the same request. Body is that original order; header `Idempotent-Replayed: true`. Nothing is charged or changed again. |

Errors. Request-format problems (400) are reported first; the business rules after them are checked in the order listed:

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | body malformed; `expectedSubtotal` missing, a number, or more than 2 decimals; malformed key |
| 400 | `IDEMPOTENCY_KEY_MISSING` | no or blank `Idempotency-Key` |
| 422 | `IDEMPOTENCY_KEY_REUSED` | key already used for a different cart, subtotal or coupon (no details, so another request's data is never revealed) |
| 404 | `CART_NOT_FOUND` | unknown cart |
| 409 | `CART_ALREADY_CHECKED_OUT` | new key for a cart that is already an order; `details.orderId` |
| 422 | `CART_EMPTY` | no items |
| 409 | `INSUFFICIENT_STOCK` | any line short; `details.shortages[]` lists all of them |
| 409 | `PRICE_CHANGED` | `details.expectedSubtotal` vs `details.currentSubtotal` |
| 404 | `COUPON_NOT_FOUND` | unknown coupon |
| 409 | `COUPON_ALREADY_REDEEMED` | coupon already used |
| 429 | `RATE_LIMITED` | this client sent too many unknown coupon codes recently (default: 10 per 15 minutes); checkouts **without** a coupon are never limited |
| 402 | `PAYMENT_FAILED` | payment declined (`details.reason`). The built-in fake payment always approves, so this is exercised by tests only. |

Any failure leaves stock, cart, coupon and orders unchanged; only a successful checkout is remembered for
the key, so a failed attempt can be retried with the same key.

Discount: `subtotal × percentOff / 100`, rounded half-up to the cent, capped at the subtotal. A 100% coupon
makes the total `0.00` and no payment is taken.

```bash
curl -s -i -X POST http://localhost:8080/carts/$CART/checkout \
     -H 'Content-Type: application/json' -H 'Idempotency-Key: 7c9e6679-7425-40de-944b-e07fc1f90ae7' \
     -d '{"expectedSubtotal":"50.00"}'
```

### `GET /orders/{orderId}`

**200** → Order. **404** `ORDER_NOT_FOUND`.

---

### `PATCH /admin/products/{productId}` *(admin)*

Changes price and/or stock. `version` must be the product version the admin loaded; omitted fields stay as
they are. Stock is set as an absolute value.

```json
{ "version": 1, "unitPrice": "27.50", "availableQty": 80 }
```

**200** → Product (with a new `version`).

| Status | Code | When |
|---|---|---|
| 400 | `VALIDATION_ERROR` | `version` missing; neither field given; price outside 0.01–9,999,999.99 or more than 2 decimals; stock < 0 or > 1,000,000 |
| 404 | `PRODUCT_NOT_FOUND` | unknown product |
| 409 | `PRODUCT_MODIFIED` | the product changed since that version (another edit or a sale); `details.currentVersion` |

### `POST /admin/coupons` *(admin)*

Generates **one** coupon for the oldest order milestone (n, 2n, 3n, …) that has been reached but not yet
rewarded. No body. Call again to pay out a further reached milestone.

**201** → Coupon. **409** `NO_ELIGIBLE_MILESTONE`:

```json
{ "code": "NO_ELIGIBLE_MILESTONE",
  "message": "No unrewarded milestone: the next coupon is earned at order #10; 7 order(s) placed so far",
  "details": { "nextMilestone": 10, "placedOrders": 7 } }
```

### `GET /admin/coupons` *(admin)*

**200** → array of Coupon, oldest milestone first.

### `GET /admin/orders` *(admin)*

**200** → array of Order, oldest first. The report below can be reconciled against this list.

### `GET /admin/report` *(admin)*

Sales summary computed from the stored orders and coupons. Read-only: requesting it never changes state.

```json
{
  "totalOrders": 6,
  "quantityByProduct": [
    { "productId": "shirt", "name": "Shirt", "quantity": 4 },
    { "productId": "socks", "name": "Socks", "quantity": 4 }
  ],
  "grossRevenue": "121.96",
  "totalDiscounts": "5.00",
  "netRevenue": "116.96",
  "coupons": { "generated": 1, "available": 0, "redeemed": 1 }
}
```

| Field | Definition |
|---|---|
| `totalOrders` | number of successfully placed orders |
| `quantityByProduct` | units sold per product (only products that sold), sorted by name |
| `grossRevenue` | sum of order subtotals, before discounts |
| `totalDiscounts` | sum of coupon discounts granted |
| `netRevenue` | sum of order totals; always `grossRevenue − totalDiscounts` |
| `coupons` | generated, still available, and redeemed (`available + redeemed = generated`) |
