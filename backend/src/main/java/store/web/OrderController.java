package store.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import store.error.ApiException;
import store.error.ErrorCode;
import store.service.CheckoutService;
import store.service.OrderService;
import store.web.dto.CheckoutRequest;
import store.web.dto.OrderResponse;

import java.net.URI;
import java.util.function.Supplier;

@RestController
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final CheckoutService checkout;
    private final OrderService orders;
    private final CouponGuessLimiter couponGuesses;

    public OrderController(CheckoutService checkout, OrderService orders, CouponGuessLimiter couponGuesses) {
        this.checkout = checkout;
        this.orders = orders;
        this.couponGuesses = couponGuesses;
    }

    /**
     * 201 for a new order; 200 with {@code Idempotent-Replayed: true} when the same Idempotency-Key and body
     * were already processed, so a client can tell "placed now" from "you already placed this".
     * The header is optional at binding time so its absence gets the specific IDEMPOTENCY_KEY_MISSING code.
     */
    @PostMapping("/carts/{cartId}/checkout")
    public ResponseEntity<OrderResponse> checkout(@PathVariable String cartId,
                                                  @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
                                                  @Valid @RequestBody CheckoutRequest request,
                                                  HttpServletRequest http) {
        CheckoutService.Result result = withCouponGuessLimit(request.couponCode(), http.getRemoteAddr(),
                () -> checkout.checkout(cartId, idempotencyKey, request.expectedSubtotal(), request.couponCode()));
        OrderResponse order = OrderResponse.from(result.order());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(order);
        }
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    /** Only requests that carry a coupon are counted or limited; unknown codes count as failed guesses. */
    private CheckoutService.Result withCouponGuessLimit(String couponCode, String client,
                                                        Supplier<CheckoutService.Result> checkoutCall) {
        if (couponCode == null || couponCode.isBlank()) {
            return checkoutCall.get();
        }
        couponGuesses.requireAllowed(client);
        try {
            return checkoutCall.get();
        } catch (ApiException e) {
            if (e.getCode() == ErrorCode.COUPON_NOT_FOUND) {
                couponGuesses.recordFailure(client);
            }
            throw e;
        }
    }

    @GetMapping("/orders/{orderId}")
    public OrderResponse get(@PathVariable String orderId) {
        return OrderResponse.from(orders.get(orderId));
    }
}
