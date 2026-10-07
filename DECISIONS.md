# Design decisions

This document explains what the service guarantees, how, and why. It covers invariants, ambiguities in the
brief and how I resolved them, ten design decisions with alternatives, the concurrency/idempotency strategy,
money rules, the error model, what is deferred, how this would evolve for production, how I used AI, and what
I would look at next.

Code paths are under `backend/src/main/java/store/`; tests under `backend/src/test/java/store/`.

---

## 1. Invariants

Each invariant is enforced in one place and proven by at least one test. Tests marked † run competing
requests on many threads at once.

| # | Invariant | Enforced in | Proven by |
|---|---|---|---|
| I1 | Inventory is never oversold | `CheckoutService.checkout`: stock check and decrement in one write-locked step | `concurrentCheckoutsNeverOversell` † (20 buyers, 3 watches → exactly 3 orders) |
| I2 | Stock is never negative | `Product` constructor rejects `availableQty < 0` | `CatalogServiceTest.invalidStockIsRejected` |
| I3 | A cart becomes at most one order | open-check and `Cart.markCheckedOut` under the write lock | `aCartCanOnlyBeCheckedOutOnce`, `concurrentCheckoutsOfOneCartCreateOneOrder` † |
| I4 | A retried checkout never creates a second order, charge or stock movement | `IdempotencyStore` lookup + record inside the checkout's write lock | `concurrentRetriesWithTheSameKeyPlaceOneOrderAndReplayIt` † |
| I5 | Invalid products or quantities never enter a cart | `CartService` (exists, 1–99, ≤ stock) + strict JSON binding | `unknownProductNeverEntersTheCart`, `invalidQuantityNeverEntersTheCart` |
| I6 | A customer is never charged a subtotal they did not see | `expectedSubtotal` compared at checkout → `PRICE_CHANGED` | `priceChangeSinceTheCustomerLookedIsRejectedThenAcceptedAtTheNewTotal` |
| I7 | An order explains its own total, forever | immutable `Order`/`OrderLine` snapshots | `orderKeepsItsSnapshotWhenTheProductChangesLater` |
| I8 | `total = subtotal − discount`, `0 ≤ discount ≤ subtotal`, `subtotal = Σ lines` | `Order` constructor rejects anything else; `Coupon.discountOn` caps | `fullDiscountMakesTheTotalZeroAndNothingIsCharged` |
| I9 | Money is exact | `BigDecimal` scale 2; `Money.parse` rejects floats and > 2 decimals | `discountRoundsHalfUpToTheCent`, `priceAsJsonNumberIsRejected` |
| I10 | A coupon is redeemed at most once | `CouponService.requireAvailable` + `redeem` inside the checkout lock | `concurrentCheckoutsCannotBothRedeemOneCoupon` † |
| I11 | A failed checkout never consumes a coupon (or stock) | coupon redeemed only in the commit step, after payment | `aFailedCheckoutDoesNotConsumeTheCoupon`, `declinedPaymentChangesNothingAndCheckoutCanBeRetried` |
| I12 | One coupon per milestone, and only once the milestone is reached | `CouponService.generate` under the write lock | `noCouponBeforeTheFirstMilestone`, `concurrentGenerationNeverDuplicatesAMilestone` † |
| I13 | The report reconciles with orders and coupons, and reading it changes nothing | `ReportService` derives everything, under the read lock | `reportReconcilesWithOrdersAndCoupons`, `repeatedReportsAreIdenticalAndChangeNothing`, `reportWaitsForAnInProgressWrite` |
| I14 | An admin edit never silently overwrites a concurrent change (including a sale) | `version` check in `CatalogService.update` | `concurrentEditsFromTheSameVersionHaveExactlyOneWinner` † |

---

## 2. Ambiguities and the semantics I chose

| Question the brief leaves open | Decision |
|---|---|
| Is a coupon created automatically at the n-th order, or when an admin asks? | When an admin asks (`POST /admin/coupons`); reaching the milestone only makes it *eligible*. |
| Orders 5 and 10 are both reached before the admin asks. One coupon or two? | **One per call**, oldest milestone first; call again for the next. Matches "an unrewarded milestone". |
| Who may use a coupon? | Anyone with the code (global bearer coupon). The brief has no customer identity to tie it to. |
| Do orders placed with a coupon count towards the next milestone? | Yes: every successfully placed order counts. |
| Expiry, stacking, scope? | No expiry; one coupon per order; it applies to the whole order subtotal. |
| What if `x = 100`? | Allowed. The total becomes `0.00` and no payment is taken. |
| Price changes after an item was added. Which price applies? | The **current** price, but only if it matches the subtotal the customer saw (`expectedSubtotal`); otherwise `409 PRICE_CHANGED` and the client shows the new total. |
| Availability changes after an item was added? | Carts do not reserve stock. Raising a line above stock is refused; lowering is always allowed. Checkout re-checks every line and rejects the whole order on any shortage (no partial fills), listing every short line. |
| How to tell a retry from a second, real checkout? | Required `Idempotency-Key`. Same key + same request → original order (200). New key on a checked-out cart → `409 CART_ALREADY_CHECKED_OUT` with the order ID. |
| Is a key per cart or global? What if the body differs? | Global. Same key with a different cart, subtotal or coupon → `422 IDEMPOTENCY_KEY_REUSED`. |
| What happens to a key whose checkout failed? | Nothing is recorded; the client may retry with the same key. |
| Can a "successfully placed" order later be cancelled? | No cancellations or refunds exist, so every placed order is final and counted in the report. |
| Payment? | A `PaymentGateway` interface with a fake that approves and records charges; declines are tested through the same interface. |
| Report definitions | gross = Σ order subtotals; discounts = Σ discounts; net = Σ totals; quantities only for products that sold; coupons available = generated − redeemed. |
| Quantity limits | 1–99 per request and per cart line (my bound, to keep input sane). |
| Coupon percentage if `x` changes later | `percentOff` is fixed on the coupon when it is generated. |

---

## 3. Material decisions

### Decision 1: One store-wide read/write lock

**Context:** Checkout touches several things that must change together: stock of every line, the cart's
status, the coupon, the order counter, the idempotency record. Admin edits, cart edits and coupon generation
read and write the same state.

**Options considered:**
- Per-entity locks (product, cart, coupon).
- Optimistic concurrency with versions and retries everywhere.
- **One lock for the whole store.**

**Choice:** `StoreLock`, a fair `ReentrantReadWriteLock`. Every check-then-change operation runs under the
write lock; reads (cart view, order, report, lists) share the read lock.

**Why:** Per-entity locks need a global lock order to avoid deadlocks (a cart with shirt + watch vs. one
with watch + shirt), and still need care for the coupon and counters. Optimistic retries push complexity into
every write path. A single lock makes every invariant in section 1 hold by construction and is easy to
reason about in review. At this scale throughput is not the constraint.

**Consequences:** All writes are serialised: correct, simple, but one slow write delays everyone. It also
does not span processes. Both are addressed in section 8, where locks move to database rows.

### Decision 2: Checkout validates everything, then commits; nothing is ever undone

**Context:** "A coupon must not be lost or consumed by a checkout that ultimately fails"; failed checkouts
must not move stock.

**Options considered:** Reserve stock and coupon, then compensate on failure; or validate first and change
nothing until everything has passed.

**Choice:** Two phases inside the write lock. (1) Validate and charge: cart open and non-empty → every line in
stock → subtotal matches → coupon available → payment approved. (2) Commit: decrement stock, redeem the
coupon, store the order, close the cart, record the idempotency key.

**Why:** With nothing changed before phase 2, every failure path is trivially clean; there is no
compensation code to get wrong. Phase 2 cannot fail on valid input (the `Order` constructor validates its
arithmetic before anything is written).

**Consequences:** Payment happens while the lock is held (see Decision 8). Stock is checked before price, so
a customer is not asked to confirm a new price for an order that would fail anyway.

### Decision 3: Required `Idempotency-Key`; only successful checkouts are remembered

**Context:** Clients retry after timeouts. A retry must not create a second order, but "a cart must not be
checked out more than once" must still hold for genuinely new attempts.

**Options considered:**
- Use the cart ID as the natural key (no header).
- Optional key.
- **Required key with the request stored alongside it.**

**Choice:** A required header. The record stores the request fields (cart, `expectedSubtotal` compared by
value, normalised coupon code) and the order ID. Same key + same request → 200 with the original order and
`Idempotent-Replayed: true`. Same key + different request → 422. Lookup and record share the checkout's
write lock.

**Why:** The cart ID cannot distinguish "I lost the response" from "someone pressed Buy again in another tab";
a client-chosen key can. Storing the fields rather than a hash lets a reused key be compared exactly.
Recording only successes means a declined payment or a price change can be fixed and retried with the same key.

**Consequences:** Clients must generate and keep a key per attempt (the frontend does, and resends
automatically on network errors). Keys never expire here; in production they would have a TTL.

### Decision 4: Charge current prices, but only the subtotal the customer saw

**Context:** The brief asks what happens when a price changes between adding to cart and checkout.

**Options considered:**
- Lock the price at add-to-cart time.
- Silently charge the current price.
- **Charge the current price, guarded by the subtotal the customer saw.**

**Choice:** Carts store only product IDs and quantities and are always priced live. Checkout requires
`expectedSubtotal`; a mismatch returns `409 PRICE_CHANGED` with both values.

**Why:** Locked prices let a stale cart buy at an old price indefinitely; silent repricing charges an amount
the customer never saw. The guard keeps the store's prices authoritative and the customer informed.

**Consequences:** One extra round trip when prices move; the frontend shows "was X, now Y" and a second click
confirms. Retries replay the original order even if prices changed since.

### Decision 5: Coupons: one per admin call, oldest milestone first, global bearer codes

**Context:** "Every n-th order makes a coupon available" plus "an administrator can request generation"
plus "only if the milestone was reached and not already rewarded".

**Options considered:** Auto-generate at the n-th order; generate all backlogged coupons per call; one per
call. Tie coupons to a customer vs. bearer codes.

**Choice:** Coupon *k* rewards order *k·n*; a coupon can be generated when `placedOrders ≥ (generated + 1)·n`;
one per call. Codes look like `REWARD-0005-K7QP` (unique by milestone, random suffix without look-alike
characters), matched case-insensitively.

**Why:** Deriving the next milestone from "coupons generated so far", under the write lock, makes duplicates
impossible without any extra bookkeeping. There is no customer model, so a bearer code is the only coherent
choice.

**Consequences:** A retried admin `POST` (lost response) may generate the *next* eligible coupon; admin
idempotency is deferred. Anyone holding a code can use it once.

### Decision 6: Money as `BigDecimal` at scale 2, strings on the wire, half-up rounding

**Context:** "Calculate money without floating-point rounding errors" and "discount calculations must be
deterministic and never make a total negative".

**Options considered:** Integer cents; `BigDecimal`; JSON numbers vs. strings.

**Choice:** `BigDecimal` at scale 2 internally; JSON strings (`"25.00"`) in both directions; JSON numbers
rejected. Discount = `subtotal × percent / 100`, rounded **HALF_UP** to the cent, capped at the subtotal.

**Why:** Strings stop JavaScript clients from turning money into floats. Rejecting extra decimals instead of
rounding avoids silent changes. HALF_UP matches what customers and receipts expect (`0.125 → 0.13`), and the
cap makes a negative total impossible.

**Consequences:** Clients must send strings, which the error message explains. Rounding happens once, on the
order-level discount; line totals (`price × integer quantity`) are always exact.

### Decision 7: Version check on admin product edits

**Context:** An admin may load stock = 3, a sale takes it to 2, then the admin saves "stock = 50"
based on the stale view.

**Options considered:**
- Last write wins.
- Relative adjustments (`+20`).
- **Absolute value plus an optimistic version check.**

**Choice:** Every product change (edit or sale) increments `version`; `PATCH` must send the version it was
based on, otherwise `409 PRODUCT_MODIFIED`.

**Why:** Last-write-wins can erase a sale's decrement; relative adjustments avoid that but make "set stock to
what I counted" awkward. A version check keeps absolute edits and detects every conflict.

**Consequences:** The admin UI reloads and asks the admin to re-apply on conflict.

### Decision 8: Fake payment called inside the checkout lock; zero totals skip payment

**Context:** No real payment provider, but checkout must be designed around payment failing.

**Options considered:** Treat checkout as paid; a payment abstraction with a fake; real provider integration.

**Choice:** `PaymentGateway` with `FakePaymentGateway`, which approves and records each charge (tests assert
"charged exactly once"). Called after all validation, before commit. A `0.00` total (100% coupon) is not
charged.

**Why:** The abstraction makes the decline path real and testable, and the recorded charges make
"no double charge" observable.

**Consequences:** Holding the lock during a remote call would be wrong in production. Section 8 describes
reserve → pay → confirm outside the lock.

### Decision 9: The report is derived, under the read lock

**Context:** "The report should reconcile with the orders and coupons returned by your system. Repeated
report requests must not mutate state."

**Options considered:** Running counters updated at checkout; computing from source data on each request.

**Choice:** Every figure is computed from the stored orders and coupons on each request, under the read lock.
`GET /admin/orders` exposes the same orders for reconciliation.

**Why:** Counters can drift from the data they summarise; a derived report cannot. The read lock means a
report never sees a coupon redeemed before its order is stored.

**Consequences:** O(orders) per request, fine here; at scale it becomes SQL aggregation or a maintained
summary (section 8).

### Decision 10: In-memory storage behind repositories

**Context:** 4–6 hour timebox; persistence is optional if invariants still hold under overlapping requests.

**Options considered:** Embedded H2 with transactions and row locks; in-memory with explicit locking.

**Choice:** `ConcurrentHashMap` repositories with immutable domain objects (except `Cart`, which is only
touched under the lock), coordinated by `StoreLock`.

**Why:** It lets the concurrency design be explicit and fully tested in the time available, rather than
delegated to configuration I could not verify as thoroughly.

**Consequences:** Data is lost on restart (the frontend drops cart IDs the backend no longer knows). The
repository boundaries are where a database would plug in.

---

## 4. Transactions, concurrency and idempotency

- **One lock, two modes.** Write lock: admin product edits, cart create/add/update/remove, checkout, coupon
  generation. Read lock: cart view, order lookups and lists, coupon list, product list, report. The lock is
  fair, so a stream of reads cannot starve a checkout.
- **Checkout = one critical section:** idempotency lookup → cart checks → stock → price → coupon → payment →
  commit (stock, coupon, order, cart status, idempotency record). Nothing changes before the commit.
- **Idempotency** lives inside that same critical section, so two requests with one key are serialised:
  the first places the order, the second finds the record and replays it.
- **No deadlocks** are possible with a single lock.
- **Evidence:** every invariant that competing requests could break has a test that starts many threads on a
  latch at the same instant (†, section 1). To check those tests can actually fail, I broke the code on
  purpose and confirmed they catch it (section 11).

## 5. Money and rounding

- `BigDecimal`, scale 2, everywhere. Line total = `unitPrice × quantity` (exact).
- Input: decimal strings with at most 2 decimals, up to 9,999,999.99; JSON numbers and extra decimals are
  rejected, never rounded. Prices must be > 0.
- Discount: `subtotal × percent / 100`, **HALF_UP** to the cent, then `min(discount, subtotal)`.
- `total = subtotal − discount`, enforced by the `Order` constructor; therefore `0.00 ≤ total ≤ subtotal`.
- Output: always strings with exactly two decimals.

## 6. Error model

- One body for every failure: `{ code, message, details }`, including unknown routes, wrong methods, bad
  media types and unexpected exceptions (which return `INTERNAL_ERROR` with no internals).
- `code` is a stable enum (`ErrorCode`) clients branch on; the HTTP status groups them: **400** malformed
  input, **402** payment declined, **404** unknown ID (with a distinct code per resource), **409** a state
  conflict the client can resolve (stock, price, coupon used, cart closed, stale version), **422**
  well-formed but unprocessable (empty cart, reused key).
- `details` is machine-readable: `fields` for validation, `shortages[]` for stock, both subtotals for a price
  change, `orderId` when a cart is already an order, `nextMilestone`/`placedOrders` for coupon generation.
- Deliberately **no details** on `IDEMPOTENCY_KEY_REUSED`: echoing the earlier request would reveal another
  customer's cart ID to whoever presents the key.
- Strict binding: unknown fields, wrong types and fractions for integers are errors, not silently accepted.
- CORS headers are applied by a servlet filter, so browsers can read error bodies too.

## 7. Implemented vs. deferred

| Implemented | Deferred (and why) |
|---|---|
| All required endpoints, plus `GET /products`, `GET /admin/orders`, `GET /admin/coupons`, `PATCH /admin/products/{id}` | Authentication/authorisation: out of scope per brief; `/admin/**` is the boundary |
| Overselling, single checkout, idempotent retries, coupon single-use, consistent report | Persistent database and multi-instance deployment (section 8) |
| Exact money, deterministic discounts | Multi-currency, taxes, shipping |
| Fake payment with a tested decline path | Real provider, payment outside the lock, refunds/cancellations |
| Idempotency for checkout | Key expiry (TTL), "in progress" state, idempotency for admin coupon generation |
| Optimistic version check for admin edits | Cart expiry/abandonment, stock reservation at add-to-cart |
| 99 backend tests; small React frontend | Frontend tests, CI pipeline, load tests, rate limiting, observability |

## 8. Multiple instances and production scale

**Storage:** PostgreSQL. The in-process lock is replaced by short database transactions and row-level
guarantees:

- **Stock:** `UPDATE product SET available_qty = available_qty - :q, version = version + 1 WHERE id = :id AND
  available_qty >= :q`. Zero rows updated = insufficient stock; the transaction rolls back.
- **One order per cart:** `UPDATE cart SET status = 'CHECKED_OUT' WHERE id = :id AND status = 'OPEN'`
  (or `SELECT … FOR UPDATE`), plus a unique constraint on `orders.cart_id`.
- **Coupon single use:** `UPDATE coupon SET status = 'REDEEMED', order_id = :o WHERE code = :c AND status =
  'AVAILABLE'`; zero rows = already used.
- **One coupon per milestone:** unique constraint on `coupon.milestone_order_number`; order numbers from a
  sequence.
- **Idempotency:** table with the key as primary key, the request fields, status (`IN_PROGRESS` / `DONE`),
  the response and an expiry. Inserting the key first means a concurrent duplicate on another instance hits
  the constraint and waits or gets "in progress", instead of placing a second order.
- **Admin edits:** same `version` column, checked in the `UPDATE`'s `WHERE` clause.

**Payment outside any lock or transaction:** create the order as `PENDING` and reserve stock and coupon in one
transaction; call the provider with the order ID as *its* idempotency key; then confirm (`PLACED`) or release
the reservations. A transactional outbox plus a reconciliation job resolves orders whose payment outcome is
unknown (timeouts).

**Report:** SQL aggregates in a single repeatable-read transaction for consistency, or a summary table updated
in the same transaction as each order if the order table grows large.

**Operations:** stateless app instances behind a load balancer, authentication for `/admin/**`, rate limits
on checkout and coupon endpoints, metrics on conflicts, declines and replays, and structured logs keyed by
order ID and idempotency key.

## 9. How I used AI tools

I used an AI coding assistant throughout. It generated most of the implementation, tests and documentation;
I owned the requirements analysis and every design decision, steered the process, and accepted each slice
only after reviewing the change and its test evidence. The work shipped as six PRs, one per slice (the skeleton and the catalogue together), each merged by me.

**How I worked with it**
- **Decisions stayed with me.** For each open question I had the assistant lay out options and trade-offs,
  then chose. Examples:
  - Spring Boot with in-memory storage, removing the pre-existing offer engine
  - Charging current prices with an `expectedSubtotal` guard
  - One coupon per admin call
  - Refusing to add beyond stock
  - A required idempotency key
  - Global bearer coupons
  - Money as strings in both directions
  - An optimistic version check for stock edits rather than last-write-wins or relative adjustments
- **I redirected it when its output did not fit.** I rejected its first implementation plan and asked for a
  vertical-slice plan with request/response JSON per endpoint. I then rejected that plan as well: I required a
  React + Vite + TypeScript frontend in its own folder, a simpler document, and that it **ask instead of
  assuming**. That last instruction changed the process: every later ambiguity (price changes, retries,
  coupon scope, money format, stock edits) became an explicit decision instead of a silent default.
- **Verification on every slice:**
  - unit and concurrency tests;
  - deliberately breaking the code to prove those tests notice;
  - live HTTP runs, including parallel requests;
  - a browser walkthrough.

**Defects in generated code that this verification caught, and what was done**
1. **A concurrency test that could not fail.** The report's stress test passed 6 out of 6 times with the read
   lock removed; the race window is microseconds wide. I did not accept it as proof: a deterministic test now
   holds the write lock and asserts the report waits. It fails without the lock (3/3) and passes with it.
2. **A data leak in an error response.** The first `IDEMPOTENCY_KEY_REUSED` response echoed the earlier
   request's cart ID; anyone presenting a used key could learn another customer's cart. The details were
   removed.
3. **Silent input coercion.** Default JSON settings truncated `1.5` to `1`, accepted `"5"` as a number and
   let `27.5` into a money string. All three are now rejected, with tests.
4. **Errors the browser could not read.** CORS was configured at the MVC layer, so error responses (e.g. a
   404) had no CORS headers and the frontend saw only "network error". Moved to a servlet filter, with a test.
5. **Tests that did not test what they claimed.** Some generated assertions were nonsensical, and one
   "malformed input" case (`"25"`) was actually valid input. Rewritten.
6. **UI details.** A conflict message disappeared when the row reloaded; a zero discount displayed as
   `−0.00`. Fixed.
7. **A misleading measurement, not a bug.** Parallel `curl` results appeared to lose responses; the cause was
   concurrent appends to one file on Windows. Re-measured with one file per request before drawing any
   conclusion.

**What I take from it:** AI output is a fast first draft, not evidence. Concurrency tests in particular have to
be shown to fail against a broken implementation before they count.

## 10. With two more hours, I would look at

1. **Payment outside the lock.** Implement reserve → pay → confirm with a timeout path, then re-run every
   concurrency test against it.
2. **PostgreSQL behind the same repositories** (Testcontainers), with the constraints in section 8, running
   the same concurrency tests against real transactions.
3. **Idempotency hardening:** expiry, an `IN_PROGRESS` state, and idempotent admin coupon generation.
4. **CI:** a GitHub Actions workflow running `./mvnw test`, the frontend build and lint on every PR, plus an
   HTTP-level concurrency smoke test.

## 11. Testing approach

- **99 backend tests:**
  - service-level rules and concurrency in plain JUnit (fast, no Spring);
  - HTTP-contract tests with MockMvc for status codes, error bodies, headers and CORS.
- **Concurrency tests** start all threads on one latch so the requests really overlap, then assert totals:
  orders, stock, charges, coupon state.
- **Tests checked against broken code:**

  | Change made on purpose | Result |
  |---|---|
  | write lock disabled | cart, checkout, idempotency and coupon race tests fail |
  | idempotency record never saved | replay tests fail |
  | coupon redeemed before payment | "failed checkout keeps the coupon" fails |
  | banker's rounding instead of HALF_UP | rounding test fails |
  | report read lock removed | only the deterministic test caught it (above) |

- **Flakiness:** all 72 service tests run 20 times back to back gave 1,440 executions with 0 failures.
- **Live runs:**
  - Over HTTP: 20 parallel checkouts for 3 watches gave 3 orders; 20 same-key retries gave 1 order and
    19 replays; 10 parallel uses of one coupon gave 1 success.
  - Reports polled during 40 concurrent checkouts saw 37 distinct in-flight states, all consistent.
