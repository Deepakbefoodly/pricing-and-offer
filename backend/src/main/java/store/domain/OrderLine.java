package store.domain;

import java.math.BigDecimal;

/**
 * What was bought, copied from the catalogue at checkout. Later price or name changes to the product
 * do not affect it, so an order can always explain its own total.
 */
public record OrderLine(String productId, String name, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {

    public OrderLine {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        if (lineTotal.compareTo(unitPrice.multiply(BigDecimal.valueOf(quantity))) != 0) {
            throw new IllegalArgumentException("lineTotal must equal unitPrice × quantity for " + productId);
        }
    }
}
