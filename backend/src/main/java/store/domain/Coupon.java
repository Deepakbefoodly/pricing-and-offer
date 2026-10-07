package store.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

/**
 * A single-use reward coupon, generated for one order milestone (the n-th, 2n-th, … placed order).
 * Immutable: redeeming produces a new instance, written back under the store lock.
 *
 * @param percentOff          discount percentage fixed when the coupon was generated (1–100)
 * @param milestoneOrderNumber the order count this coupon rewards; unique across coupons
 */
public record Coupon(String code, int percentOff, long milestoneOrderNumber, CouponStatus status, Instant generatedAt,
                     String redeemedByOrderId, Instant redeemedAt) {

    public Coupon {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (percentOff < 1 || percentOff > 100) {
            throw new IllegalArgumentException("percentOff must be 1-100: " + percentOff);
        }
        if ((status == CouponStatus.REDEEMED) != (redeemedByOrderId != null)) {
            throw new IllegalArgumentException("a coupon has a redeeming order exactly when it is REDEEMED");
        }
    }

    public static Coupon issue(String code, int percentOff, long milestoneOrderNumber, Instant generatedAt) {
        return new Coupon(code, percentOff, milestoneOrderNumber, CouponStatus.AVAILABLE, generatedAt, null, null);
    }

    public boolean isAvailable() {
        return status == CouponStatus.AVAILABLE;
    }

    public Coupon redeem(String orderId, Instant at) {
        if (!isAvailable()) {
            throw new IllegalStateException("Coupon " + code + " is already redeemed");
        }
        return new Coupon(code, percentOff, milestoneOrderNumber, CouponStatus.REDEEMED, generatedAt, orderId, at);
    }

    /**
     * {@code subtotal × percentOff / 100}, rounded HALF_UP to cents and capped at the subtotal,
     * so the discounted total is never negative. Deterministic: same subtotal, same discount.
     */
    public BigDecimal discountOn(BigDecimal subtotal) {
        BigDecimal discount = subtotal.multiply(BigDecimal.valueOf(percentOff))
                .divide(BigDecimal.valueOf(100), Money.SCALE, RoundingMode.HALF_UP);
        return discount.min(subtotal).setScale(Money.SCALE);
    }
}
