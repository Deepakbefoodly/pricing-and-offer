package store.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import store.domain.Coupon;
import store.domain.Order;
import store.domain.Product;
import store.error.ApiException;
import store.payment.FakePaymentGateway;
import store.repository.CartRepository;
import store.repository.CouponRepository;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    private ProductRepository products;
    private CatalogService catalog;
    private CartService carts;
    private OrderRepository orders;
    private CouponRepository couponRepository;
    private CouponService coupons;
    private CheckoutService checkout;
    private ReportService reports;
    private StoreLock lock;

    @BeforeEach
    void setUp() {
        products = new ProductRepository();
        products.add(Product.create("shirt", "Shirt", "25.00", 1000));
        products.add(Product.create("socks", "Socks", "5.49", 1000));
        products.add(Product.create("watch", "Limited Watch", "199.00", 1));
        lock = new StoreLock();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        catalog = new CatalogService(products, lock);
        carts = new CartService(new CartRepository(), catalog, lock);
        orders = new OrderRepository();
        couponRepository = new CouponRepository();
        coupons = new CouponService(couponRepository, orders, lock, clock, 2, 10);
        checkout = new CheckoutService(carts, catalog, products, orders, new IdempotencyStore(), coupons,
                new FakePaymentGateway(), lock, clock);
        reports = new ReportService(orders, couponRepository, lock);
    }

    @Test
    void emptyStoreReportsZeros() {
        Report report = reports.summary();

        assertThat(report.totalOrders()).isZero();
        assertThat(report.quantityByProduct()).isEmpty();
        assertThat(report.grossRevenue()).isEqualTo("0.00");
        assertThat(report.totalDiscounts()).isEqualTo("0.00");
        assertThat(report.netRevenue()).isEqualTo("0.00");
        assertThat(report.coupons()).isEqualTo(new Report.CouponCounts(0, 0, 0));
    }

    @Test
    void reportReconcilesWithOrdersAndCoupons() {
        place(Map.of("shirt", 2), "50.00", null);                 // order 1
        place(Map.of("socks", 3), "16.47", null);                 // order 2 -> milestone 2
        String code = coupons.generate().code();
        place(Map.of("shirt", 1, "socks", 1), "30.49", code);     // order 3, 10% -> 3.05 off
        place(Map.of("shirt", 1), "25.00", null);                 // order 4 -> milestone 4
        coupons.generate();                                       // generated, not used
        // Failed checkouts must leave no trace in the report.
        String cartId = cartWith(Map.of("watch", 1));
        catalog.update("watch", 1, null, 0);
        assertThatThrownBy(() -> checkout.checkout(cartId, "k-fail", "199.00", null)).isInstanceOf(ApiException.class);

        Report report = reports.summary();

        assertThat(report.totalOrders()).isEqualTo(4);
        assertThat(report.quantityByProduct()).containsExactly(
                new Report.ProductQuantity("shirt", "Shirt", 4),
                new Report.ProductQuantity("socks", "Socks", 4));
        assertThat(report.grossRevenue()).isEqualTo("121.96");
        assertThat(report.totalDiscounts()).isEqualTo("3.05");
        assertThat(report.netRevenue()).isEqualTo("118.91");
        assertThat(report.coupons()).isEqualTo(new Report.CouponCounts(2, 1, 1));

        // Reconcile independently against the stored orders and coupons.
        List<Order> stored = orders.findAll();
        assertThat(report.grossRevenue()).isEqualByComparingTo(sum(stored, "subtotal"));
        assertThat(report.totalDiscounts()).isEqualByComparingTo(sum(stored, "discount"));
        assertThat(report.netRevenue()).isEqualByComparingTo(sum(stored, "total"));
        assertThat(report.grossRevenue().subtract(report.totalDiscounts())).isEqualByComparingTo(report.netRevenue());
        assertThat(report.coupons().redeemed()).isEqualTo(stored.stream().filter(o -> o.coupon() != null).count());
        assertThat(report.quantityByProduct().stream().mapToLong(Report.ProductQuantity::quantity).sum())
                .isEqualTo(stored.stream().flatMap(o -> o.lines().stream()).mapToLong(l -> l.quantity()).sum());
    }

    @Test
    void repeatedReportsAreIdenticalAndChangeNothing() {
        place(Map.of("shirt", 2), "50.00", null);
        place(Map.of("socks", 1), "5.49", null);
        coupons.generate();
        List<Order> ordersBefore = orders.findAll();
        List<Coupon> couponsBefore = couponRepository.findAll();
        List<Product> productsBefore = products.findAll();

        Report first = reports.summary();
        Report second = reports.summary();

        assertThat(second).isEqualTo(first);
        assertThat(orders.findAll()).isEqualTo(ordersBefore);
        assertThat(couponRepository.findAll()).isEqualTo(couponsBefore);
        assertThat(products.findAll()).isEqualTo(productsBefore);
    }

    @Test
    void revenueUsesThePricePaidNotTheCurrentPrice() {
        place(Map.of("shirt", 1), "25.00", null);
        catalog.update("shirt", products.findById("shirt").orElseThrow().version(), "30.00", null);

        assertThat(reports.summary().grossRevenue()).isEqualTo("25.00"); // revenue at the price actually paid
    }

    /**
     * The mechanism behind consistent reports, tested deterministically: while a write (e.g. a checkout midway
     * through its commit) holds the store lock, a report must wait instead of reading half-applied state.
     * The stress test below exercises the same rule but cannot reliably hit the microsecond-wide window.
     */
    @Test
    void reportWaitsForAnInProgressWrite() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch finishWrite = new CountDownLatch(1);
        Future<?> writer = pool.submit(() -> lock.write(() -> {
            writeStarted.countDown();
            try {
                finishWrite.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(writeStarted.await(5, TimeUnit.SECONDS)).isTrue();

        Future<Report> report = pool.submit(reports::summary);
        assertThatThrownBy(() -> report.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        finishWrite.countDown();
        assertThat(report.get(5, TimeUnit.SECONDS)).isNotNull();
        writer.get(5, TimeUnit.SECONDS);
        pool.shutdown();
    }

    /**
     * Twenty customers check out at once, each buying one shirt with their own 10% coupon, while an admin keeps
     * refreshing the report. Every report must describe one consistent moment: as many orders as redeemed coupons
     * and shirts sold, and exactly 2.50 discount per order.
     */
    @Test
    void reportsTakenDuringConcurrentCheckoutsAreConsistent() throws Exception {
        int customers = 20;
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            Coupon coupon = Coupon.issue("C-" + i, 10, i + 1, NOW);
            couponRepository.add(coupon);
            codes.add(coupon.code());
        }
        List<String> cartIds = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            cartIds.add(cartWith(Map.of("shirt", 1)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(customers + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean checkoutsDone = new AtomicBoolean(false);
        AtomicInteger reportsTaken = new AtomicInteger();
        Future<?> reader = pool.submit(() -> {
            start.await();
            while (!checkoutsDone.get()) {
                Report report = reports.summary();
                long shirts = report.quantityByProduct().stream().mapToLong(Report.ProductQuantity::quantity).sum();
                assertThat(report.coupons().redeemed()).isEqualTo(report.totalOrders());
                assertThat(shirts).isEqualTo(report.totalOrders());
                assertThat(report.totalDiscounts())
                        .isEqualByComparingTo(new BigDecimal("2.50").multiply(BigDecimal.valueOf(report.totalOrders())));
                assertThat(report.grossRevenue().subtract(report.totalDiscounts())).isEqualByComparingTo(report.netRevenue());
                reportsTaken.incrementAndGet();
            }
            return null;
        });
        List<Future<?>> writers = new ArrayList<>();
        for (int i = 0; i < customers; i++) {
            String cartId = cartIds.get(i);
            String code = codes.get(i);
            writers.add(pool.submit(() -> {
                start.await();
                return checkout.checkout(cartId, UUID.randomUUID().toString(), "25.00", code);
            }));
        }
        start.countDown();
        for (Future<?> writer : writers) {
            writer.get(10, TimeUnit.SECONDS);
        }
        checkoutsDone.set(true);
        reader.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(reportsTaken.get()).isPositive();
        Report finalReport = reports.summary();
        assertThat(finalReport.totalOrders()).isEqualTo(customers);
        assertThat(finalReport.coupons()).isEqualTo(new Report.CouponCounts(customers, 0, customers));
    }

    private void place(Map<String, Integer> items, String expectedSubtotal, String couponCode) {
        checkout.checkout(cartWith(items), UUID.randomUUID().toString(), expectedSubtotal, couponCode);
    }

    private String cartWith(Map<String, Integer> items) {
        String cartId = carts.create().id();
        items.forEach((productId, quantity) -> carts.addItem(cartId, productId, quantity));
        return cartId;
    }

    private static BigDecimal sum(List<Order> orders, String field) {
        return orders.stream().map(o -> switch (field) {
            case "subtotal" -> o.subtotal();
            case "discount" -> o.discount();
            default -> o.total();
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
