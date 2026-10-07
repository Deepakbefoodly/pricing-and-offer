package store.service;

import org.springframework.stereotype.Service;
import store.domain.Coupon;
import store.domain.Money;
import store.domain.Order;
import store.domain.OrderLine;
import store.repository.CouponRepository;
import store.repository.OrderRepository;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Admin sales report. Read-only: it only reads repositories, never writes, so requesting it any number of
 * times cannot change state. It takes the read lock so that orders and coupons are read at one consistent
 * moment; without it, a report taken mid-checkout could count a redeemed coupon whose order is not stored yet.
 */
@Service
public class ReportService {

    private final OrderRepository orders;
    private final CouponRepository coupons;
    private final StoreLock lock;

    public ReportService(OrderRepository orders, CouponRepository coupons, StoreLock lock) {
        this.orders = orders;
        this.coupons = coupons;
        this.lock = lock;
    }

    public Report summary() {
        return lock.read(() -> build(orders.findAll(), coupons.findAll()));
    }

    static Report build(List<Order> orders, List<Coupon> coupons) {
        // Only products that were actually sold appear; the name is taken from the order snapshot.
        Map<String, Report.ProductQuantity> byProduct = new LinkedHashMap<>();
        for (Order order : orders) {
            for (OrderLine line : order.lines()) {
                byProduct.merge(line.productId(), new Report.ProductQuantity(line.productId(), line.name(), line.quantity()),
                        (sold, more) -> new Report.ProductQuantity(sold.productId(), more.name(), sold.quantity() + more.quantity()));
            }
        }
        List<Report.ProductQuantity> quantities = byProduct.values().stream()
                .sorted(Comparator.comparing(Report.ProductQuantity::name).thenComparing(Report.ProductQuantity::productId))
                .toList();

        long redeemed = coupons.stream().filter(coupon -> !coupon.isAvailable()).count();
        return new Report(
                orders.size(),
                quantities,
                sum(orders, Order::subtotal),
                sum(orders, Order::discount),
                sum(orders, Order::total),
                new Report.CouponCounts(coupons.size(), coupons.size() - redeemed, redeemed));
    }

    private static BigDecimal sum(List<Order> orders, Function<Order, BigDecimal> amount) {
        return orders.stream().map(amount).reduce(BigDecimal.ZERO.setScale(Money.SCALE), BigDecimal::add);
    }
}
