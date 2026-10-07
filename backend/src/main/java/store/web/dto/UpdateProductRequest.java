package store.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Admin product edit. {@code version} is the version the admin last saw (optimistic check);
 * {@code unitPrice} is a decimal string; omitted fields stay unchanged.
 */
public record UpdateProductRequest(@NotNull Long version, String unitPrice, Integer availableQty) {
}
