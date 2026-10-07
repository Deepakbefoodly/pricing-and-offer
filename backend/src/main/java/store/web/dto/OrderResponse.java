package store.web.dto;

import store.domain.Money;
import store.domain.Order;

import java.time.Instant;
import java.util.List;

public record OrderResponse(String id, long orderNumber, String cartId, List<Line> lines, String subtotal,
                            Coupon coupon, String discount, String total, Instant placedAt) {

    public record Line(String productId, String name, String unitPrice, int quantity, String lineTotal) {
    }

    public record Coupon(String code, int percentOff) {
    }

    public static OrderResponse from(Order order) {
        List<Line> lines = order.lines().stream()
                .map(line -> new Line(line.productId(), line.name(), Money.format(line.unitPrice()), line.quantity(),
                        Money.format(line.lineTotal())))
                .toList();
        Coupon coupon = order.coupon() == null ? null : new Coupon(order.coupon().code(), order.coupon().percentOff());
        return new OrderResponse(order.id(), order.orderNumber(), order.cartId(), lines, Money.format(order.subtotal()),
                coupon, Money.format(order.discount()), Money.format(order.total()), order.placedAt());
    }
}
