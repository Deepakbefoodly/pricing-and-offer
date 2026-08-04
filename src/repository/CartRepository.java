package repository;

import entity.Cart;
import exception.CartNotFoundException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CartRepository {

    private final Map<String, Cart> carts = new ConcurrentHashMap<>();

    public Cart createCart(String cartId) {
        Cart cart = new Cart(cartId);
        Cart existing = carts.putIfAbsent(cartId, cart);
        if (existing != null) {
            throw new IllegalArgumentException("Cart already exists: " + cartId);
        }
        return cart;
    }

    public Cart getCart(String cartId) {
        Cart cart = carts.get(cartId);
        if (cart == null) {
            throw new CartNotFoundException(cartId);
        }
        return cart;
    }
}
