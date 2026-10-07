package store.service;

import store.domain.Cart;
import store.domain.CartStatus;
import store.domain.Money;
import store.domain.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;

/**
 * Immutable snapshot of a cart priced at current product prices. Built while the store lock is held,
 * so it is internally consistent and safe to hand to the web layer afterwards.
 */
public record CartView(String id, CartStatus status, String orderId, List<Line> lines, int itemCount, BigDecimal subtotal) {

    public record Line(String productId, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal,
                       int availableQty) {

        /** False when stock dropped below the cart quantity after the item was added; checkout would fail. */
        public boolean inStock() {
            return quantity <= availableQty;
        }
    }

    static CartView of(Cart cart, Function<String, Product> products) {
        List<Line> lines = cart.items().entrySet().stream()
                .map(item -> line(products.apply(item.getKey()), item.getValue()))
                .toList();
        int itemCount = lines.stream().mapToInt(Line::quantity).sum();
        BigDecimal subtotal = lines.stream()
                .map(Line::lineTotal)
                .reduce(BigDecimal.ZERO.setScale(Money.SCALE), BigDecimal::add);
        return new CartView(cart.id(), cart.status(), cart.orderId(), lines, itemCount, subtotal);
    }

    private static Line line(Product product, int quantity) {
        // Scale-2 price × integer quantity stays at scale 2: exact, no rounding involved.
        BigDecimal lineTotal = product.unitPrice().multiply(BigDecimal.valueOf(quantity));
        return new Line(product.id(), product.name(), product.unitPrice(), quantity, lineTotal, product.availableQty());
    }
}
