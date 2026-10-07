package store.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.repository.CartRepository;
import store.repository.ProductRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CartServiceTest {

    private CatalogService catalog;
    private CartRepository cartRepository;
    private CartService carts;
    private String cartId;

    @BeforeEach
    void setUp() {
        ProductRepository products = new ProductRepository();
        products.add(Product.create("shirt", "Shirt", "25.00", 100));
        products.add(Product.create("socks", "Socks", "5.49", 200));
        products.add(Product.create("watch", "Limited Watch", "199.00", 3));
        products.add(Product.create("hoodie", "Hoodie", "39.00", 0));
        StoreLock lock = new StoreLock();
        catalog = new CatalogService(products, lock);
        cartRepository = new CartRepository();
        carts = new CartService(cartRepository, catalog, lock);
        cartId = carts.create().id();
    }

    @Test
    void newCartIsOpenAndEmpty() {
        CartView cart = carts.get(cartId);
        assertThat(cart.status().name()).isEqualTo("OPEN");
        assertThat(cart.lines()).isEmpty();
        assertThat(cart.subtotal()).isEqualTo("0.00");
    }

    @Test
    void addingTheSameProductMergesQuantitiesAndKeepsInsertionOrder() {
        carts.addItem(cartId, "socks", 2);
        carts.addItem(cartId, "shirt", 1);
        CartView cart = carts.addItem(cartId, "socks", 1);

        assertThat(cart.lines()).extracting(CartView.Line::productId).containsExactly("socks", "shirt");
        assertThat(cart.lines().get(0).quantity()).isEqualTo(3);
        assertThat(cart.itemCount()).isEqualTo(4);
        // 3 × 5.49 + 25.00, exact to the cent
        assertThat(cart.lines().get(0).lineTotal()).isEqualTo("16.47");
        assertThat(cart.subtotal()).isEqualTo("41.47");
    }

    @Test
    void cartIsPricedAtTheCurrentPrice() {
        carts.addItem(cartId, "shirt", 2);
        catalog.update("shirt", 1, "30.00", null);

        CartView cart = carts.get(cartId);
        assertThat(cart.lines().get(0).unitPrice()).isEqualTo("30.00");
        assertThat(cart.subtotal()).isEqualTo("60.00");
    }

    @Test
    void unknownProductNeverEntersTheCart() {
        assertError(() -> carts.addItem(cartId, "jacket", 1), ErrorCode.PRODUCT_NOT_FOUND);
        assertThat(carts.get(cartId).lines()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, CartService.MAX_LINE_QUANTITY + 1})
    void invalidQuantityNeverEntersTheCart(int quantity) {
        assertError(() -> carts.addItem(cartId, "shirt", quantity), ErrorCode.VALIDATION_ERROR);
        assertThat(carts.get(cartId).lines()).isEmpty();
    }

    @Test
    void mergedQuantityIsCappedPerLine() {
        carts.addItem(cartId, "shirt", 90);
        assertError(() -> carts.addItem(cartId, "shirt", 10), ErrorCode.VALIDATION_ERROR);
        assertThat(carts.get(cartId).lines().get(0).quantity()).isEqualTo(90);
    }

    @Test
    void addingMoreThanAvailableStockIsRejected() {
        carts.addItem(cartId, "watch", 2);

        ApiException e = assertError(() -> carts.addItem(cartId, "watch", 2), ErrorCode.INSUFFICIENT_STOCK);
        assertThat(e.getDetails().get("shortages"))
                .isEqualTo(List.of(Map.of("productId", "watch", "requested", 4, "available", 3)));
        assertError(() -> carts.addItem(cartId, "hoodie", 1), ErrorCode.INSUFFICIENT_STOCK);
        assertThat(carts.get(cartId).itemCount()).isEqualTo(2);
    }

    @Test
    void setQuantityChangesAnExistingLine() {
        carts.addItem(cartId, "shirt", 1);
        CartView cart = carts.setQuantity(cartId, "shirt", 4);
        assertThat(cart.lines().get(0).quantity()).isEqualTo(4);
        assertThat(cart.subtotal()).isEqualTo("100.00");
    }

    @Test
    void setQuantityRequiresTheItemToBeInTheCart() {
        assertError(() -> carts.setQuantity(cartId, "shirt", 2), ErrorCode.CART_ITEM_NOT_FOUND);
        assertError(() -> carts.setQuantity(cartId, "jacket", 2), ErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    void afterStockDropsTheLineIsFlaggedAndCanStillBeReduced() {
        carts.addItem(cartId, "watch", 3);
        catalog.update("watch", 1, null, 1); // stock drops after the item was added

        CartView flagged = carts.get(cartId);
        assertThat(flagged.lines().get(0).inStock()).isFalse();

        assertError(() -> carts.setQuantity(cartId, "watch", 4), ErrorCode.INSUFFICIENT_STOCK);
        CartView reduced = carts.setQuantity(cartId, "watch", 2); // still above stock, but moving towards it
        assertThat(reduced.lines().get(0).quantity()).isEqualTo(2);
        assertThat(carts.setQuantity(cartId, "watch", 1).lines().get(0).inStock()).isTrue();
    }

    @Test
    void removeDeletesTheLine() {
        carts.addItem(cartId, "shirt", 1);
        carts.addItem(cartId, "socks", 1);

        CartView cart = carts.removeItem(cartId, "shirt");
        assertThat(cart.lines()).extracting(CartView.Line::productId).containsExactly("socks");
        assertError(() -> carts.removeItem(cartId, "shirt"), ErrorCode.CART_ITEM_NOT_FOUND);
    }

    @Test
    void unknownCartIsNotFound() {
        assertError(() -> carts.get("cart_missing"), ErrorCode.CART_NOT_FOUND);
        assertError(() -> carts.addItem("cart_missing", "shirt", 1), ErrorCode.CART_NOT_FOUND);
    }

    @Test
    void checkedOutCartCannotChange() {
        carts.addItem(cartId, "shirt", 1);
        cartRepository.findById(cartId).orElseThrow().markCheckedOut("ord_1");

        ApiException e = assertError(() -> carts.addItem(cartId, "socks", 1), ErrorCode.CART_NOT_OPEN);
        assertThat(e.getDetails()).containsEntry("orderId", "ord_1");
        assertError(() -> carts.setQuantity(cartId, "shirt", 2), ErrorCode.CART_NOT_OPEN);
        assertError(() -> carts.removeItem(cartId, "shirt"), ErrorCode.CART_NOT_OPEN);
        assertThat(carts.get(cartId).itemCount()).isEqualTo(1);
    }

    /** Twenty concurrent "add one shirt" requests on the same cart: no update may be lost. */
    @Test
    void concurrentAddsToOneCartAreNotLost() throws Exception {
        List<Boolean> results = runConcurrently(20, () -> {
            carts.addItem(cartId, "shirt", 1);
            return true;
        });

        assertThat(results).hasSize(20).containsOnly(true);
        assertThat(carts.get(cartId).lines().get(0).quantity()).isEqualTo(20);
    }

    /** Ten concurrent "add one watch" requests with only 3 in stock: exactly 3 may succeed. */
    @Test
    void concurrentAddsCannotExceedStock() throws Exception {
        List<Boolean> results = runConcurrently(10, () -> {
            try {
                carts.addItem(cartId, "watch", 1);
                return true;
            } catch (ApiException e) {
                assertThat(e.getCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
                return false;
            }
        });

        assertThat(results.stream().filter(ok -> ok).count()).isEqualTo(3);
        assertThat(carts.get(cartId).lines().get(0).quantity()).isEqualTo(3);
    }

    private static ApiException assertError(Runnable action, ErrorCode expected) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(expected);
            caught[0] = e;
        });
        return caught[0];
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
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
