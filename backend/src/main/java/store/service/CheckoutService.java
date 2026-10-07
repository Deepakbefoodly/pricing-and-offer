package store.service;

import org.springframework.stereotype.Service;
import store.domain.Cart;
import store.domain.Money;
import store.domain.Order;
import store.domain.OrderLine;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.payment.PaymentDeclinedException;
import store.payment.PaymentGateway;
import store.repository.OrderRepository;
import store.repository.ProductRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 */
@Service
public class CheckoutService {

    private final CartService carts;
    private final CatalogService catalog;
    private final ProductRepository products;
    private final OrderRepository orders;
    private final PaymentGateway payments;
    private final StoreLock lock;
    private final Clock clock;

    public CheckoutService(CartService carts, CatalogService catalog, ProductRepository products, OrderRepository orders,
                           PaymentGateway payments, StoreLock lock, Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.products = products;
        this.orders = orders;
        this.payments = payments;
        this.lock = lock;
        this.clock = clock;
    }

    /**
     * @param expectedSubtotal the subtotal the customer was shown; checkout refuses to charge a different amount
     */
    public Order checkout(String cartId, String expectedSubtotal) {
        BigDecimal expected = Money.parse(expectedSubtotal, "expectedSubtotal");
        return lock.write(() -> {
            // 1. Validate and charge: no state changes in this block.
            Cart cart = carts.requireOpenCart(cartId);
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
            return order;
        });
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
