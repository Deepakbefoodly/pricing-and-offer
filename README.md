# Checkout & Rewards Service

Backend for an e-commerce store: carts, checkout, orders, and a reward program that makes an x%-off coupon
available after every n-th successfully placed order. The focus is predictable behaviour under retries,
concurrent checkouts, inventory changes and competing coupon use. A small React frontend demonstrates it.

- **API reference:** [`docs/API.md`](docs/API.md)
- **Design decisions, invariants, trade-offs:** [`DECISIONS.md`](DECISIONS.md)
- **Assignment and build plan:** [`docs/TASK.md`](docs/TASK.md), [`docs/PLAN.md`](docs/PLAN.md)

Approximate time spent: **~6 hours**.

## What's implemented

- Products with price and stock; carts (create, view, add, change quantity, remove) priced at current prices
- Checkout that never oversells, never charges a subtotal the customer didn't see, and leaves no trace when it fails
- Safe retries: `Idempotency-Key` on checkout; a retried request returns the original order instead of a new one
- Immutable orders that explain their own totals; exact money arithmetic (`BigDecimal`, sent as strings)
- Reward coupons: generated per milestone by an admin, single use, never consumed by a failed checkout
- Admin: product price/stock edits (version-checked), coupon generation and listing, order listing, sales report
- 117 automated tests, including concurrency tests for every invariant that competing requests could break,
  run both against the services and over real HTTP

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 |
| Node.js | 20.19+ or 22.12+ (required by Vite 8) |

Maven is not needed: the backend ships with the Maven wrapper (`mvnw` / `mvnw.cmd`).

## Run it

```bash
# Backend: http://localhost:8080
cd backend
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

```bash
# Frontend: http://localhost:5173
cd frontend
npm install
npm run dev
```

The backend's CORS policy allows `http://localhost:5173`, so the frontend dev server is pinned to that port.
Data lives in memory: restarting the backend resets it to the seed data.

## Test

```bash
cd backend
./mvnw test                   # 117 tests: unit, concurrency, HTTP-contract and HTTP concurrency tests
```

```bash
cd frontend
npm run build                 # type-check + production build
npm run lint
```

## Configuration

Backend settings live in `backend/src/main/resources/application.yml`. Any of them can be overridden with
environment variables:

| Setting | Default | Environment variable | Meaning |
|---|---|---|---|
| `store.rewards.n` | `5` | `STORE_REWARDS_N` | every n-th placed order earns a coupon (≥ 1) |
| `store.rewards.x` | `10` | `STORE_REWARDS_X` | coupon discount in percent (1–100) |
| `store.seed.enabled` | `true` | `STORE_SEED_ENABLED` | load the demo catalogue at startup |
| `store.coupon-guessing.max-failed-attempts` | `10` | `STORE_COUPONGUESSING_MAXFAILEDATTEMPTS` | unknown coupon codes one client (IP) may try per window before its coupon checkouts get `429` |
| `store.coupon-guessing.window` | `15m` | `STORE_COUPONGUESSING_WINDOW` | length of that window |
| `store.cors.allowed-origins` | `http://localhost:5173` | — | origins allowed to call the API from a browser |

Invalid values (for example `n = 0`) stop the backend at startup rather than failing later.

Frontend: the API base URL defaults to `http://localhost:8080`. Override it with `VITE_API_BASE_URL`
(see `frontend/.env.example`).

## Seed data

| ID | Name | Price | Stock | Purpose |
|---|---|---|---|---|
| `shirt` | Shirt | 25.00 | 100 | |
| `jeans` | Jeans | 49.99 | 50 | |
| `shoes` | Shoes | 89.90 | 30 | |
| `socks` | Socks | 5.49 | 200 | odd price, exercises rounding |
| `watch` | Limited Watch | 199.00 | 3 | **limited inventory**, for overselling tests |
| `hoodie` | Hoodie | 39.00 | 0 | sold out |

## Demo walkthrough

### In the browser (http://localhost:5173)

1. **Products:** add a few items. The nav badge shows the cart count.
2. **Cart:** change quantities, then **Checkout**. You land on the order page.
3. **Order page → Retry demo:** "Simulate retry (same key)" returns the same order (HTTP 200, replayed).
   "New attempt (new key)" is refused with `CART_ALREADY_CHECKED_OUT`.
4. **Price change:** add an item, then in **Admin → Products** raise its price and press **Checkout** in the
   cart. The checkout is refused, showing old vs. new subtotal; pressing Checkout again pays the new total.
5. **Coupons:** place 5 orders, then **Admin → Coupons → Generate coupon**. Enter the code at checkout
   (any letter case). Reusing it is refused.
6. **Admin → Report:** totals, units sold and coupon counts, next to the orders they are computed from.

## Frontend scripts

Run from `frontend/`:

| Command | What it does |
|---|---|
| `npm run dev` | dev server on http://localhost:5173 (port is fixed; see CORS above) |
| `npm run build` | type-check and production build into `dist/` |
| `npm run lint` | oxlint |
| `npm run preview` | serve the production build locally |
