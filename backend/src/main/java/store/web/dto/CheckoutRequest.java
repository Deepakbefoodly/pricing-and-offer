package store.web.dto;

import jakarta.validation.constraints.NotNull;

/** {@code expectedSubtotal} is the cart subtotal the customer saw, as a decimal string ("50.00"). */
public record CheckoutRequest(@NotNull String expectedSubtotal) {
}
