package store.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param expectedSubtotal the cart subtotal the customer saw (before any coupon), as a decimal string ("50.00")
 * @param couponCode       optional reward coupon code
 */
public record CheckoutRequest(@NotNull String expectedSubtotal, String couponCode) {
}
