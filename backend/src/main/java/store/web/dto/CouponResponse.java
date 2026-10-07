package store.web.dto;

import store.domain.Coupon;
import store.domain.CouponStatus;

import java.time.Instant;

public record CouponResponse(String code, int percentOff, long milestoneOrderNumber, CouponStatus status,
                             Instant generatedAt, String redeemedByOrderId, Instant redeemedAt) {

    public static CouponResponse from(Coupon coupon) {
        return new CouponResponse(coupon.code(), coupon.percentOff(), coupon.milestoneOrderNumber(), coupon.status(),
                coupon.generatedAt(), coupon.redeemedByOrderId(), coupon.redeemedAt());
    }
}
