package store.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An immutable, successfully placed order. It carries everything needed to explain its total:
 * the line snapshots, the subtotal, the coupon (if any), the discount and the final total.
 * The constructor enforces the arithmetic so an inconsistent order cannot exist.
 *
 * @param orderNumber store-wide sequence (1, 2, 3, …) in the order checkouts were committed
 */
public record Order(String id, long orderNumber, String cartId, List<OrderLine> lines, BigDecimal subtotal,
                    AppliedCoupon coupon, BigDecimal discount, BigDecimal total, Instant placedAt) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(cartId, "cartId");
        Objects.requireNonNull(placedAt, "placedAt");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        BigDecimal linesTotal = lines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (subtotal.compareTo(linesTotal) != 0) {
            throw new IllegalArgumentException("subtotal " + subtotal + " != sum of lines " + linesTotal);
        }
        if (discount.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw new IllegalArgumentException("discount must be between 0 and the subtotal: " + discount);
        }
        if (total.compareTo(subtotal.subtract(discount)) != 0) {
            throw new IllegalArgumentException("total must equal subtotal - discount");
        }
    }
}
