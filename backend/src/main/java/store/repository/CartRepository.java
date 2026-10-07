package store.repository;

import org.springframework.stereotype.Repository;
import store.domain.Cart;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class CartRepository {

    private final Map<String, Cart> carts = new ConcurrentHashMap<>();

    public void add(Cart cart) {
        if (carts.putIfAbsent(cart.id(), cart) != null) {
            throw new IllegalArgumentException("Cart already exists: " + cart.id());
        }
    }

    public Optional<Cart> findById(String id) {
        return Optional.ofNullable(carts.get(id));
    }
}
