package store.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import store.domain.Coupon;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.payment.FakePaymentGateway;
import store.repository.CartRepository;
import store.repository.CouponRepository;
import store.repository.IdempotencyStore;
import store.repository.OrderRepository;
import store.repository.ProductRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Milestone rules with n = 2 (every 2nd order earns a coupon) and x = 10%. */
class CouponServiceTest {

    private CartService carts;
    private CheckoutService checkout;
    private CouponService coupons;

    @BeforeEach
    void setUp() {
        ProductRepository products = new ProductRepository();
        products.add(Product.create("shirt", "Shirt", "25.00", 1000));
        StoreLock lock = new StoreLock();
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
        CatalogService catalog = new CatalogService(products, lock);
        carts = new CartService(new CartRepository(), catalog, lock);
        OrderRepository orders = new OrderRepository();
        coupons = new CouponService(new CouponRepository(), orders, lock, clock, 2, 10);
        checkout = new CheckoutService(carts, catalog, products, orders, new IdempotencyStore(), coupons,
                new FakePaymentGateway(), lock, clock);
    }

    @Test
    void noCouponBeforeTheFirstMilestone() {
        placeOrders(1);

        ApiException e = assertError(() -> coupons.generate(), ErrorCode.NO_ELIGIBLE_MILESTONE);
        assertThat(e.getDetails()).containsEntry("nextMilestone", 2L).containsEntry("placedOrders", 1L);
        assertThat(coupons.list()).isEmpty();
    }

    @Test
    void eachMilestoneIsRewardedOnceOldestFirst() {
        placeOrders(4); // milestones 2 and 4 reached, nothing generated yet

        Coupon first = coupons.generate();
        Coupon second = coupons.generate();
        ApiException e = assertError(() -> coupons.generate(), ErrorCode.NO_ELIGIBLE_MILESTONE);

        assertThat(first.milestoneOrderNumber()).isEqualTo(2);
        assertThat(second.milestoneOrderNumber()).isEqualTo(4);
        assertThat(first.percentOff()).isEqualTo(10);
        assertThat(first.code()).matches("REWARD-0002-[A-Z2-9]{10}");
        assertThat(e.getDetails()).containsEntry("nextMilestone", 6L);
    }

    @Test
    void ordersPlacedWithACouponStillCountTowardsTheNextMilestone() {
        placeOrders(2);
        String code = coupons.generate().code();
        String cartId = carts.create().id();
        carts.addItem(cartId, "shirt", 1);
        checkout.checkout(cartId, UUID.randomUUID().toString(), "25.00", code);
        placeOrders(1); // orders: 2 + 1 with coupon + 1 = 4

        assertThat(coupons.generate().milestoneOrderNumber()).isEqualTo(4);
    }

    /** Ten admins press "Generate" at once with two milestones reached: exactly two coupons, no duplicates. */
    @Test
    void concurrentGenerationNeverDuplicatesAMilestone() throws Exception {
        placeOrders(4);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    coupons.generate();
                    return true;
                } catch (ApiException e) {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.NO_ELIGIBLE_MILESTONE);
                    return false;
                }
            }));
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long created = 0;
        for (Future<Boolean> result : results) {
            created += result.get() ? 1 : 0;
        }
        assertThat(created).isEqualTo(2);
        assertThat(coupons.list()).extracting(Coupon::milestoneOrderNumber).containsExactly(2L, 4L);
    }

    @Test
    void listingIsOldestMilestoneFirstAndChangesNothing() {
        placeOrders(4);
        coupons.generate();
        coupons.generate();

        assertThat(coupons.list()).extracting(Coupon::milestoneOrderNumber).containsExactly(2L, 4L);
        assertThat(coupons.list()).isEqualTo(coupons.list());
    }

    private void placeOrders(int count) {
        for (int i = 0; i < count; i++) {
            String cartId = carts.create().id();
            carts.addItem(cartId, "shirt", 1);
            checkout.checkout(cartId, UUID.randomUUID().toString(), "25.00", null);
        }
    }

    private static ApiException assertError(Runnable action, ErrorCode expected) {
        ApiException[] caught = new ApiException[1];
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getCode()).isEqualTo(expected);
            caught[0] = e;
        });
        return caught[0];
    }
}
