package store.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import store.service.CheckoutService;
import store.service.OrderService;
import store.web.dto.CheckoutRequest;
import store.web.dto.OrderResponse;

import java.net.URI;

@RestController
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final CheckoutService checkout;
    private final OrderService orders;

    public OrderController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    /**
     * 201 for a new order; 200 with {@code Idempotent-Replayed: true} when the same Idempotency-Key and body
     * were already processed, so a client can tell "placed now" from "you already placed this".
     * The header is optional at binding time so its absence gets the specific IDEMPOTENCY_KEY_MISSING code.
     */
    @PostMapping("/carts/{cartId}/checkout")
    public ResponseEntity<OrderResponse> checkout(@PathVariable String cartId,
                                                  @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                                  @Valid @RequestBody CheckoutRequest request) {
        CheckoutService.Result result = checkout.checkout(cartId, idempotencyKey, request.expectedSubtotal(), request.couponCode());
        OrderResponse order = OrderResponse.from(result.order());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(order);
        }
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(@PathVariable String orderId) {
        return OrderResponse.from(orders.get(orderId));
    }
}
