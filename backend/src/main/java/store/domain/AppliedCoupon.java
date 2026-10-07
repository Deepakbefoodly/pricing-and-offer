package store.domain;

/** The coupon an order used, copied onto the order (populated once coupons exist). */
public record AppliedCoupon(String code, int percentOff) {
}
