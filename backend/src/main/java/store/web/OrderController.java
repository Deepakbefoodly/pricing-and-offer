package store.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import store.service.CheckoutService;
import store.service.OrderService;
import store.web.dto.CheckoutRequest;
import store.web.dto.OrderResponse;

import java.net.URI;

@RestController
public class OrderController {

    private final CheckoutService checkout;
    private final OrderService orders;

    public OrderController(CheckoutService checkout, OrderService orders) {
        this.checkout = checkout;
        this.orders = orders;
    }

    @PostMapping("/carts/{cartId}/checkout")
    public ResponseEntity<OrderResponse> checkout(@PathVariable String cartId, @Valid @RequestBody CheckoutRequest request) {
        OrderResponse order = OrderResponse.from(checkout.checkout(cartId, request.expectedSubtotal()));
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(@PathVariable String orderId) {
        return OrderResponse.from(orders.get(orderId));
    }
}
