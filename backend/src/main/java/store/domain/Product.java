package store.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable product snapshot. Changes produce a new instance with {@code version + 1},
 * which lets an admin edit detect that someone else (or a checkout) changed the product first.
 */
public record Product(String id, String name, BigDecimal unitPrice, int availableQty, long version) {

    public Product {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.signum() <= 0 || unitPrice.scale() != Money.SCALE) {
            throw new IllegalArgumentException("unitPrice must be positive with scale " + Money.SCALE + ": " + unitPrice);
        }
        if (availableQty < 0) {
            throw new IllegalArgumentException("availableQty must be >= 0: " + availableQty);
        }
    }

    public static Product create(String id, String name, String unitPrice, int availableQty) {
        return new Product(id, name, Money.parse(unitPrice, "unitPrice"), availableQty, 1);
    }

    public Product withPriceAndStock(BigDecimal newUnitPrice, int newAvailableQty) {
        return new Product(id, name, newUnitPrice, newAvailableQty, version + 1);
    }
}
