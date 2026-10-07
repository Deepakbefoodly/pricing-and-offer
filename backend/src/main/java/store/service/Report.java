package store.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sales summary, derived entirely from stored orders and coupons (there are no separate counters that
 * could drift). Built under the store read lock, so every figure comes from the same moment.
 *
 * @param grossRevenue   sum of order subtotals, before discounts
 * @param totalDiscounts sum of coupon discounts granted
 * @param netRevenue     sum of order totals; always grossRevenue - totalDiscounts
 */
public record Report(long totalOrders, List<ProductQuantity> quantityByProduct, BigDecimal grossRevenue,
                     BigDecimal totalDiscounts, BigDecimal netRevenue, CouponCounts coupons) {

    public record ProductQuantity(String productId, String name, long quantity) {
    }

    public record CouponCounts(long generated, long available, long redeemed) {
    }
}
