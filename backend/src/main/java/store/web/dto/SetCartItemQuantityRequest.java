package store.web.dto;

import jakarta.validation.constraints.NotNull;

public record SetCartItemQuantityRequest(@NotNull Integer quantity) {
}
