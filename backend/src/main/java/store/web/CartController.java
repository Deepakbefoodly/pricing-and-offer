package store.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.service.CartService;
import store.web.dto.AddCartItemRequest;
import store.web.dto.CartResponse;
import store.web.dto.SetCartItemQuantityRequest;

import java.net.URI;

@RestController
@RequestMapping("/carts")
public class CartController {

    private final CartService carts;

    public CartController(CartService carts) {
        this.carts = carts;
    }

    @PostMapping
    public ResponseEntity<CartResponse> create() {
        CartResponse cart = CartResponse.from(carts.create());
        return ResponseEntity.created(URI.create("/carts/" + cart.id())).body(cart);
    }

    @GetMapping("/{cartId}")
    public CartResponse get(@PathVariable String cartId) {
        return CartResponse.from(carts.get(cartId));
    }

    @PostMapping("/{cartId}/items")
    public CartResponse addItem(@PathVariable String cartId, @Valid @RequestBody AddCartItemRequest request) {
        return CartResponse.from(carts.addItem(cartId, request.productId(), request.quantity()));
    }

    @PutMapping("/{cartId}/items/{productId}")
    public CartResponse setQuantity(@PathVariable String cartId, @PathVariable String productId,
                                    @Valid @RequestBody SetCartItemQuantityRequest request) {
        return CartResponse.from(carts.setQuantity(cartId, productId, request.quantity()));
    }

    @DeleteMapping("/{cartId}/items/{productId}")
    public CartResponse removeItem(@PathVariable String cartId, @PathVariable String productId) {
        return CartResponse.from(carts.removeItem(cartId, productId));
    }
}
