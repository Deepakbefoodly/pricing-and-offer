package store.repository;

import org.springframework.stereotype.Repository;
import store.domain.Coupon;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class CouponRepository {

    private final Map<String, Coupon> coupons = new ConcurrentHashMap<>();

    public void add(Coupon coupon) {
        if (coupons.putIfAbsent(coupon.code(), coupon) != null) {
            throw new IllegalArgumentException("Coupon already exists: " + coupon.code());
        }
    }

    public void replace(Coupon coupon) {
        if (coupons.replace(coupon.code(), coupon) == null) {
            throw new IllegalArgumentException("Coupon does not exist: " + coupon.code());
        }
    }

    public Optional<Coupon> findByCode(String code) {
        return Optional.ofNullable(coupons.get(code));
    }

    public long count() {
        return coupons.size();
    }

    /** Oldest milestone first. */
    public List<Coupon> findAll() {
        return coupons.values().stream().sorted(Comparator.comparingLong(Coupon::milestoneOrderNumber)).toList();
    }
}
