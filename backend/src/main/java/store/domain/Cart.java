package store.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A cart holds product IDs and quantities only, never prices: prices are looked up when the cart is
 * viewed or checked out, so a cart always reflects the current catalogue.
 * <p>
 * Not thread-safe on its own. Every access goes through {@link store.service.StoreLock}
 * (reads under the read lock, changes under the write lock).
 */
public class Cart {

    private final String id;
    private final Map<String, Integer> items = new LinkedHashMap<>(); // insertion order = display order
    private CartStatus status = CartStatus.OPEN;
    private String orderId;

    public Cart(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    public String id() {
        return id;
    }

    public CartStatus status() {
        return status;
    }

    public boolean isOpen() {
        return status == CartStatus.OPEN;
    }

    /** The order created when this cart was checked out, or null while it is open. */
    public String orderId() {
        return orderId;
    }

    public Map<String, Integer> items() {
        return Collections.unmodifiableMap(items);
    }

    public int quantityOf(String productId) {
        return items.getOrDefault(productId, 0);
    }

    public boolean contains(String productId) {
        return items.containsKey(productId);
    }

    public void setQuantity(String productId, int quantity) {
        requireOpen();
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive: " + quantity);
        }
        items.put(productId, quantity);
    }

    public void remove(String productId) {
        requireOpen();
        items.remove(productId);
    }

    public void markCheckedOut(String orderId) {
        requireOpen();
        this.orderId = Objects.requireNonNull(orderId, "orderId");
        this.status = CartStatus.CHECKED_OUT;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("Cart " + id + " is " + status);
        }
    }
}
