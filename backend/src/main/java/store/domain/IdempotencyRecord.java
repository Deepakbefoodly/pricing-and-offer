package store.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Remembers which order a successful checkout request produced, keyed by the client's Idempotency-Key.
 * The request fields are kept (not just a hash) so a reused key can be compared exactly and explained.
 */
public record IdempotencyRecord(String key, String cartId, BigDecimal expectedSubtotal, String orderId, Instant createdAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(cartId, "cartId");
        Objects.requireNonNull(expectedSubtotal, "expectedSubtotal");
        Objects.requireNonNull(orderId, "orderId");
    }

    /** True when a request carries the same intent as the one that created this record. */
    public boolean matches(String cartId, BigDecimal expectedSubtotal) {
        return this.cartId.equals(cartId) && this.expectedSubtotal.compareTo(expectedSubtotal) == 0;
    }
}
