package store.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import store.domain.Order;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.payment.FakePaymentGateway;
import store.payment.PaymentDeclinedException;
import store.payment.PaymentGateway;
import store.repository.CartRepository;
import store.repository.IdempotencyStore;
import store.repository.OrderRepository;
import store.repository.ProductRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:15:30Z");

    private ProductRepository products;
    private CatalogService catalog;
    private CartService carts;
    private OrderRepository orders;
    private IdempotencyStore idempotency;
    private FakePaymentGateway fakePayments;
    private AtomicBoolean declinePayments;
    private CheckoutService checkout;

    @BeforeEach
    void setUp() {
        products = new ProductRepository();
        products.add(Product.create("shirt", "Shirt", "25.00", 100));
        products.add(Product.create("socks", "Socks", "5.49", 200));
        products.add(Product.create("watch", "Limited Watch", "199.00", 3));
        StoreLock lock = new StoreLock();
        catalog = new CatalogService(products, lock);
        carts = new CartService(new CartRepository(), catalog, lock);
        orders = new OrderRepository();
        idempotency = new IdempotencyStore();
        fakePayments = new FakePaymentGateway();
        declinePayments = new AtomicBoolean(false);
        PaymentGateway payments = (orderId, amount) -> {
            if (declinePayments.get()) {
                throw new PaymentDeclinedException("card declined");
            }
            fakePayments.charge(orderId, amount);
        };
        checkout = new CheckoutService(carts, catalog, products, orders, idempotency, payments, lock, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void checkoutCreatesAnOrderDecrementsStockAndClosesTheCart() {
        String cartId = cartWith(Map.of("shirt", 2, "socks", 3));

        Order order = place(cartId, "66.47");

        assertThat(order.orderNumber()).isEqualTo(1);
        assertThat(order.cartId()).isEqualTo(cartId);
        assertThat(order.lines()).extracting(l -> l.productId() + "×" + l.quantity() + "=" + l.lineTotal())
                .containsExactlyInAnyOrder("shirt×2=50.00", "socks×3=16.47");
        assertThat(order.subtotal()).isEqualTo("66.47");
        assertThat(order.discount()).isEqualTo("0.00");
        assertThat(order.total()).isEqualTo("66.47");
        assertThat(order.placedAt()).isEqualTo(NOW);

        assertThat(product("shirt").availableQty()).isEqualTo(98);
        assertThat(product("socks").availableQty()).isEqualTo(197);
        assertThat(product("shirt").version()).isEqualTo(2); // stock movement bumps the version admins edit against
        CartView cart = carts.get(cartId);
        assertThat(cart.status().name()).isEqualTo("CHECKED_OUT");
        assertThat(cart.orderId()).isEqualTo(order.id());
        assertThat(fakePayments.charges()).containsExactly(new FakePaymentGateway.Charge(order.id(), new BigDecimal("66.47")));
    }

    @Test
    void orderKeepsItsSnapshotWhenTheProductChangesLater() {
        String cartId = cartWith(Map.of("shirt", 2));
        Order order = place(cartId, "50.00");

        catalog.update("shirt", product("shirt").version(), "99.00", null);

        assertThat(orders.findById(order.id()).orElseThrow().lines().get(0).unitPrice()).isEqualTo("25.00");
        assertThat(orders.findById(order.id()).orElseThrow().total()).isEqualTo("50.00");
    }

    @Test
    void priceChangeSinceTheCustomerLookedIsRejectedThenAcceptedAtTheNewTotal() {
        String cartId = cartWith(Map.of("shirt", 2));
        catalog.update("shirt", 1, "26.00", null);

        ApiException e = assertError(() -> place(cartId, "50.00"), ErrorCode.PRICE_CHANGED);
        assertThat(e.getDetails()).containsEntry("expectedSubtotal", "50.00").containsEntry("currentSubtotal", "52.00");
        assertNothingHappened(cartId, "shirt", 100);

        assertThat(place(cartId, "52.00").total()).isEqualTo("52.00");
    }

    @Test
    void stockShortageRejectsTheWholeOrderAndListsEveryShortLine() {
        String cartId = cartWith(Map.of("watch", 3, "shirt", 1));
        catalog.update("watch", 1, null, 1); // sold elsewhere / stock correction after the item was added

        ApiException e = assertError(() -> place(cartId, "622.00"), ErrorCode.INSUFFICIENT_STOCK);
        assertThat(e.getDetails().get("shortages"))
                .isEqualTo(List.of(Map.of("productId", "watch", "requested", 3, "available", 1)));
        assertNothingHappened(cartId, "shirt", 100);
        assertThat(product("watch").availableQty()).isEqualTo(1);
    }

    @Test
    void declinedPaymentChangesNothingAndCheckoutCanBeRetried() {
        String cartId = cartWith(Map.of("watch", 1));
        declinePayments.set(true);

        ApiException e = assertError(() -> place(cartId, "199.00"), ErrorCode.PAYMENT_FAILED);
        assertThat(e.getDetails()).containsEntry("reason", "card declined");
        assertNothingHappened(cartId, "watch", 3);

        declinePayments.set(false);
        assertThat(place(cartId, "199.00").orderNumber()).isEqualTo(1);
        assertThat(product("watch").availableQty()).isEqualTo(2);
    }

    @Test
    void emptyCartCannotBeCheckedOut() {
        String cartId = carts.create().id();
        assertError(() -> place(cartId, "0.00"), ErrorCode.CART_EMPTY);
    }

    @Test
    void unknownCartAndMalformedSubtotalAreRejected() {
        assertError(() -> place("cart_missing", "1.00"), ErrorCode.CART_NOT_FOUND);
        String cartId = cartWith(Map.of("shirt", 1));
        assertError(() -> place(cartId, "25.001"), ErrorCode.VALIDATION_ERROR);
        assertNothingHappened(cartId, "shirt", 100);
    }

    @Test
    void aCartCanOnlyBeCheckedOutOnce() {
        String cartId = cartWith(Map.of("shirt", 1));
        Order first = place(cartId, "25.00");

        ApiException e = assertError(() -> place(cartId, "25.00"), ErrorCode.CART_ALREADY_CHECKED_OUT);
        assertThat(e.getDetails()).containsEntry("orderId", first.id());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(product("shirt").availableQty()).isEqualTo(99);
    }

    @Test
    void orderNumbersFollowCommitOrder() {
        Order first = place(cartWith(Map.of("shirt", 1)), "25.00");
        Order second = place(cartWith(Map.of("socks", 1)), "5.49");
        assertThat(List.of(first.orderNumber(), second.orderNumber())).containsExactly(1L, 2L);
    }

    /** Twenty customers each check out the limited watch (stock 3) at the same moment. */
    @Test
    void concurrentCheckoutsNeverOversell() throws Exception {
        List<String> cartIds = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cartIds.add(cartWith(Map.of("watch", 1))); // carts don't reserve stock, so all 20 can hold one
        }

        List<Optional<Order>> results = runConcurrently(cartIds.stream().<Callable<Optional<Order>>>map(cartId -> () -> {
            try {
                return Optional.of(place(cartId, "199.00"));
            } catch (ApiException e) {
                assertThat(e.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                return Optional.empty();
            }
        }).toList());

        List<Order> placed = results.stream().flatMap(Optional::stream).toList();
        assertThat(placed).hasSize(3);
        assertThat(placed).extracting(Order::orderNumber).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(product("watch").availableQty()).isZero();
        assertThat(orders.count()).isEqualTo(3);
        assertThat(fakePayments.charges()).hasSize(3);
    }

    /** The same cart submitted ten times at once with different keys (e.g. two tabs): exactly one order. */
    @Test
    void concurrentCheckoutsOfOneCartCreateOneOrder() throws Exception {
        String cartId = cartWith(Map.of("shirt", 1));

        List<Optional<Order>> results = runConcurrently(java.util.stream.IntStream.range(0, 10)
                .<Callable<Optional<Order>>>mapToObj(i -> () -> {
                    try {
                        return Optional.of(place(cartId, "25.00"));
                    } catch (ApiException e) {
                        assertThat(e.getCode()).isEqualTo(ErrorCode.CART_ALREADY_CHECKED_OUT);
                        return Optional.empty();
                    }
                }).toList());

        assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(fakePayments.charges()).hasSize(1);
        assertThat(product("shirt").availableQty()).isEqualTo(99);
    }

    // --- Idempotent retries -------------------------------------------------------------------------------

    /** A client times out and its retries race the original: one order, one charge, one stock movement. */
    @Test
    void concurrentRetriesWithTheSameKeyPlaceOneOrderAndReplayIt() throws Exception {
        String cartId = cartWith(Map.of("watch", 1));

        List<CheckoutService.Result> results = runConcurrently(java.util.stream.IntStream.range(0, 10)
                .<Callable<CheckoutService.Result>>mapToObj(i -> () -> checkout.checkout(cartId, "key-1", "199.00"))
                .toList());

        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(results).filteredOn(CheckoutService.Result::replayed).hasSize(9);
        assertThat(results).extracting(r -> r.order().id()).containsOnly(results.get(0).order().id());
        assertThat(orders.count()).isEqualTo(1);
        assertThat(fakePayments.charges()).hasSize(1);
        assertThat(product("watch").availableQty()).isEqualTo(2);
    }

    @Test
    void retryReturnsTheOriginalOrderEvenAfterPricesChange() {
        String cartId = cartWith(Map.of("shirt", 2));
        Order original = checkout.checkout(cartId, "key-1", "50.00").order();
        catalog.update("shirt", product("shirt").version(), "30.00", null);

        CheckoutService.Result retry = checkout.checkout(cartId, "key-1", "50.00");

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.order()).isEqualTo(original);
        assertThat(fakePayments.charges()).hasSize(1);
    }

    @Test
    void retryMatchesTheSubtotalByValueNotByText() {
        String cartId = cartWith(Map.of("shirt", 2));
        checkout.checkout(cartId, "key-1", "50.00");
        assertThat(checkout.checkout(cartId, "key-1", "50").replayed()).isTrue();
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String cartId = cartWith(Map.of("shirt", 2));
        checkout.checkout(cartId, "key-1", "50.00");
        String otherCart = cartWith(Map.of("shirt", 2));

        assertError(() -> checkout.checkout(cartId, "key-1", "49.00"), ErrorCode.IDEMPOTENCY_KEY_REUSED);
        ApiException e = assertError(() -> checkout.checkout(otherCart, "key-1", "50.00"), ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertThat(e.getDetails()).isEmpty(); // never reveals the other request's cart
        assertThat(carts.get(otherCart).status().name()).isEqualTo("OPEN");
        assertThat(orders.count()).isEqualTo(1);
    }

    @Test
    void aFailedAttemptIsNotRecordedSoTheSameKeyCanBeRetried() {
        String cartId = cartWith(Map.of("watch", 1));
        declinePayments.set(true);
        assertError(() -> checkout.checkout(cartId, "key-1", "199.00"), ErrorCode.PAYMENT_FAILED);

        declinePayments.set(false);
        CheckoutService.Result retry = checkout.checkout(cartId, "key-1", "199.00");

        assertThat(retry.replayed()).isFalse();
        assertThat(orders.count()).isEqualTo(1);
        assertThat(fakePayments.charges()).hasSize(1);
    }

    @Test
    void aNewKeyForACheckedOutCartPointsToItsOrder() {
        String cartId = cartWith(Map.of("shirt", 1));
        Order order = checkout.checkout(cartId, "key-1", "25.00").order();

        ApiException e = assertError(() -> checkout.checkout(cartId, "key-2", "25.00"), ErrorCode.CART_ALREADY_CHECKED_OUT);
        assertThat(e.getDetails()).containsEntry("orderId", order.id());
    }

    @Test
    void theKeyIsRequiredAndValidated() {
        String cartId = cartWith(Map.of("shirt", 1));
        assertError(() -> checkout.checkout(cartId, null, "25.00"), ErrorCode.IDEMPOTENCY_KEY_MISSING);
        assertError(() -> checkout.checkout(cartId, "  ", "25.00"), ErrorCode.IDEMPOTENCY_KEY_MISSING);
        assertError(() -> checkout.checkout(cartId, "has space", "25.00"), ErrorCode.VALIDATION_ERROR);
        assertError(() -> checkout.checkout(cartId, "k".repeat(CheckoutService.MAX_KEY_LENGTH + 1), "25.00"),
                ErrorCode.VALIDATION_ERROR);
        assertThat(orders.count()).isZero();
    }

    /** One checkout attempt with its own fresh key, i.e. not a retry. */
    private Order place(String cartId, String expectedSubtotal) {
        return checkout.checkout(cartId, UUID.randomUUID().toString(), expectedSubtotal).order();
    }

    private String cartWith(Map<String, Integer> items) {
        String cartId = carts.create().id();
        items.forEach((productId, quantity) -> carts.addItem(cartId, productId, quantity));
        return cartId;
    }

    private Product product(String id) {
        return products.findById(id).orElseThrow();
    }

    private void assertNothingHappened(String cartId, String productId, int expectedStock) {
        assertThat(carts.get(cartId).status().name()).isEqualTo("OPEN");
        assertThat(orders.count()).isZero();
        assertThat(fakePayments.charges()).isEmpty();
        assertThat(product(productId).availableQty()).isEqualTo(expectedStock);
    }

    private static ApiException assertError(Runnable action, ErrorCode expected) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(expected);
            caught[0] = e;
        });
        return caught[0];
    }

    private static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }
}
