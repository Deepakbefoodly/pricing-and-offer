package store.service;

import org.springframework.stereotype.Service;
import store.domain.Cart;
import store.domain.Product;
import store.error.ApiException;
import store.error.ErrorCode;
import store.repository.CartRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cart operations. Every change runs under the store write lock, so the checks below (cart open,
 * product exists, quantity limits, stock) and the change itself happen as one atomic step.
 * Stock is checked for early feedback only; it is not reserved, and checkout checks it again.
 */
@Service
public class CartService {

    static final int MAX_LINE_QUANTITY = 99;

    private final CartRepository carts;
    private final CatalogService catalog;
    private final StoreLock lock;

    public CartService(CartRepository carts, CatalogService catalog, StoreLock lock) {
        this.carts = carts;
        this.catalog = catalog;
        this.lock = lock;
    }

    public CartView create() {
        Cart cart = new Cart("cart_" + UUID.randomUUID().toString().replace("-", ""));
        return lock.write(() -> {
            carts.add(cart);
            return view(cart);
        });
    }

    public CartView get(String cartId) {
        return lock.read(() -> view(requireCart(cartId)));
    }

    /** Adds {@code quantity} units, merging with any quantity already in the cart. */
    public CartView addItem(String cartId, String productId, int quantity) {
        requireValidQuantity(quantity);
        return lock.write(() -> {
            Cart cart = requireOpenCart(cartId);
            Product product = catalog.require(productId);
            int current = cart.quantityOf(productId);
            int requested = current + quantity;
            if (requested > MAX_LINE_QUANTITY) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "A cart can hold at most " + MAX_LINE_QUANTITY + " of one product",
                        Map.of("fields", Map.of("quantity", "cart already has " + current + "; max " + MAX_LINE_QUANTITY)));
            }
            requireStock(product, current, requested);
            cart.setQuantity(productId, requested);
            return view(cart);
        });
    }

    /** Sets the quantity of an item already in the cart. */
    public CartView setQuantity(String cartId, String productId, int quantity) {
        requireValidQuantity(quantity);
        return lock.write(() -> {
            Cart cart = requireOpenCart(cartId);
            requireItem(cart, productId);
            requireStock(catalog.require(productId), cart.quantityOf(productId), quantity);
            cart.setQuantity(productId, quantity);
            return view(cart);
        });
    }

    public CartView removeItem(String cartId, String productId) {
        return lock.write(() -> {
            Cart cart = requireOpenCart(cartId);
            requireItem(cart, productId);
            cart.remove(productId);
            return view(cart);
        });
    }

    private CartView view(Cart cart) {
        return CartView.of(cart, catalog::require);
    }

    /** Looks up a cart or fails with CART_NOT_FOUND. Caller holds the lock. */
    Cart requireCart(String cartId) {
        return carts.findById(cartId)
                .orElseThrow(() -> new ApiException(ErrorCode.CART_NOT_FOUND, "Cart not found: " + cartId,
                        Map.of("cartId", cartId)));
    }

    /** Looks up a cart that can still change, or fails with CART_NOT_FOUND / CART_NOT_OPEN. Caller holds the lock. */
    Cart requireOpenCart(String cartId) {
        Cart cart = requireCart(cartId);
        if (!cart.isOpen()) {
            Map<String, Object> details = new HashMap<>(Map.of("cartId", cartId, "status", cart.status()));
            if (cart.orderId() != null) {
                details.put("orderId", cart.orderId());
            }
            throw new ApiException(ErrorCode.CART_NOT_OPEN, "Cart " + cartId + " is " + cart.status() + " and can no longer change",
                    details);
        }
        return cart;
    }

    private static void requireItem(Cart cart, String productId) {
        if (!cart.contains(productId)) {
            throw new ApiException(ErrorCode.CART_ITEM_NOT_FOUND, "Product '" + productId + "' is not in the cart",
                    Map.of("cartId", cart.id(), "productId", productId));
        }
    }

    private static void requireValidQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_LINE_QUANTITY) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid quantity",
                    Map.of("fields", Map.of("quantity", "must be between 1 and " + MAX_LINE_QUANTITY)));
        }
    }

    /**
     * Rejects a quantity above current stock. Reductions are always allowed, so a customer whose
     * line went out of stock after adding it can still lower the quantity towards what is available.
     */
    private static void requireStock(Product product, int current, int requested) {
        if (requested > current && requested > product.availableQty()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_STOCK,
                    "Not enough stock for '" + product.id() + "': requested " + requested + ", available " + product.availableQty(),
                    Map.of("shortages", List.of(Map.of(
                            "productId", product.id(),
                            "requested", requested,
                            "available", product.availableQty()))));
        }
    }
}
