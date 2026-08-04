package entity;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Cart {

    private final String cartId;
    private final Map<String, Integer> items = new ConcurrentHashMap<>();

    public Cart(String cartId) {
        this.cartId = cartId;
    }

    public void addItem(String productId, int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be >0, was "+ quantity);
        }
        // used merge because it is atomic per key on ConcurrentHashMap,
        items.merge(productId, quantity, Integer::sum);
    }

    public Map<String, Integer> getItems() {
        return Collections.unmodifiableMap(items);
    }

    public String getCartId() {
        return cartId;
    }
}
