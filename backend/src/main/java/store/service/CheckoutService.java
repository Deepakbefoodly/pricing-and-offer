package store.service;

import org.springframework.stereotype.Service;
import store.domain.Cart;
import store.domain.IdempotencyRecord;
import store.domain.Money;
import store.domain.Order;
import store.domain.OrderLine;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.payment.PaymentDeclinedException;
import store.payment.PaymentGateway;
import store.repository.IdempotencyStore;
import store.repository.OrderRepository;
import store.repository.ProductRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns an open cart into an order. The whole checkout runs under the store write lock and is split in two:
 * <ol>
 *   <li><b>Validate and charge</b>: cart open and non-empty, every line in stock, the subtotal the customer saw
 *       still matches, payment approved. Nothing in the store has changed yet, so any failure here leaves
 *       stock, cart and orders exactly as they were.</li>
 *   <li><b>Commit</b>: decrement stock, store the order, mark the cart checked out. These steps cannot fail
 *       on valid input, so an order and its stock movement always happen together.</li>
 * </ol>
 * Holding the lock across the payment call is acceptable for the in-memory fake; a real provider call would
 * move outside the lock behind a reservation (see DECISIONS.md).
 * <p>
 * Retries: a request carries an Idempotency-Key. If that key already produced an order, the original order is
 * returned unchanged ({@link Result#replayed()}), so a client that lost the response can safely resend.
 */
@Service
public class CheckoutService {

    static final int MAX_KEY_LENGTH = 100;
    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1," + MAX_KEY_LENGTH + "}");

    private final CartService carts;
    private final CatalogService catalog;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final IdempotencyStore idempotency;
    private final PaymentGateway payments;
    private final StoreLock lock;
    private final Clock clock;

    public CheckoutService(CartService carts, CatalogService catalog, ProductRepository products, OrderRepository orders,
                           IdempotencyStore idempotency, PaymentGateway payments, StoreLock lock, Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.products = products;
        this.orders = orders;
        this.idempotency = idempotency;
        this.payments = payments;
        this.lock = lock;
        this.clock = clock;
    }

    /** The order a checkout request resulted in, and whether it was replayed from an earlier identical request. */
    public record Result(Order order, boolean replayed) {
    }

    /**
     * Places the order, or replays the earlier result when the same request is retried.
     * <p>
     * The key lookup and the record write happen under the same write lock as the checkout itself, so two
     * concurrent requests with one key cannot both pass the lookup: the second waits and then sees the record.
     * Only successful checkouts are recorded, so a request that failed (e.g. payment declined) can be retried
     * with the same key.
     *
     * @param idempotencyKey   client-chosen key identifying this checkout attempt across retries
     * @param expectedSubtotal the subtotal the customer was shown; checkout refuses to charge a different amount
     */
    public Result checkout(String cartId, String idempotencyKey, String expectedSubtotal) {
        String key = requireValidKey(idempotencyKey);
        BigDecimal expected = Money.parse(expectedSubtotal, "expectedSubtotal");
        return lock.write(() -> {
            // 0. A retry of a request that already succeeded gets the original order back, whatever changed since.
            Optional<IdempotencyRecord> previous = idempotency.find(key);
            if (previous.isPresent()) {
                if (!previous.get().matches(cartId, expected)) {
                    // No details: echoing the earlier request would hand its cart ID to whoever presents the key.
                    throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                            "Idempotency-Key was already used for a different checkout request; use a new key for a new attempt");
                }
                return new Result(orders.findById(previous.get().orderId()).orElseThrow(), true);
            }

            // 1. Validate and charge: no state changes in this block.
            Cart cart = carts.requireCart(cartId);
            if (!cart.isOpen()) {
                // A new attempt (different key) for a cart that already became an order.
                throw new ApiException(ErrorCode.CART_ALREADY_CHECKED_OUT, "Cart " + cartId + " was already checked out",
                        Map.of("cartId", cartId, "orderId", cart.orderId()));
            }
            if (cart.items().isEmpty()) {
                throw new ApiException(ErrorCode.CART_EMPTY, "Cart " + cartId + " has no items", Map.of("cartId", cartId));
            }
            CartView priced = CartView.of(cart, catalog::require);
            requireStock(priced);
            if (priced.subtotal().compareTo(expected) != 0) {
                throw new ApiException(ErrorCode.PRICE_CHANGED,
                        "Prices changed since the cart was viewed: expected " + Money.format(expected)
                                + ", current " + Money.format(priced.subtotal()),
                        Map.of("expectedSubtotal", Money.format(expected), "currentSubtotal", Money.format(priced.subtotal())));
            }

            BigDecimal discount = BigDecimal.ZERO.setScale(Money.SCALE);
            BigDecimal total = priced.subtotal().subtract(discount);
            Order order = new Order(
                    "ord_" + UUID.randomUUID().toString().replace("-", ""),
                    orders.count() + 1,
                    cartId,
                    priced.lines().stream().map(CheckoutService::snapshot).toList(),
                    priced.subtotal(),
                    null,
                    discount,
                    total,
                    Instant.now(clock));

            try {
                payments.charge(order.id(), order.total());
            } catch (PaymentDeclinedException e) {
                throw new ApiException(ErrorCode.PAYMENT_FAILED, "Payment was declined: " + e.getMessage(),
                        Map.of("reason", e.getMessage()));
            }

            // 2. Commit.
            for (OrderLine line : order.lines()) {
                Product product = catalog.require(line.productId());
                products.replace(product.withPriceAndStock(product.unitPrice(), product.availableQty() - line.quantity()));
            }
            orders.add(order);
            cart.markCheckedOut(order.id());
            idempotency.add(new IdempotencyRecord(key, cartId, expected, order.id(), order.placedAt()));
            return new Result(order, false);
        });
    }

    private static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISSING,
                    "Checkout requires an Idempotency-Key header; reuse the same key when retrying", Map.of());
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid Idempotency-Key",
                    Map.of("fields", Map.of("Idempotency-Key", "1-" + MAX_KEY_LENGTH + " printable ASCII characters, no spaces")));
        }
        return key;
    }

    private static void requireStock(CartView cart) {
        List<Map<String, Object>> shortages = cart.lines().stream()
                .filter(line -> !line.inStock())
                .map(line -> Map.<String, Object>of(
                        "productId", line.productId(),
                        "requested", line.quantity(),
                        "available", line.availableQty()))
                .toList();
        if (!shortages.isEmpty()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Not enough stock for " + shortages.size() + " item(s) in the cart", Map.of("shortages", shortages));
        }
    }

    private static OrderLine snapshot(CartView.Line line) {
        return new OrderLine(line.productId(), line.name(), line.unitPrice(), line.quantity(), line.lineTotal());
    }
}
