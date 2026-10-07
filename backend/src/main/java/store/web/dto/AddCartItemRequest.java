package store.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Quantity range is enforced by the service so the rule lives in one place. */
public record AddCartItemRequest(@NotBlank String productId, @NotNull Integer quantity) {
}
