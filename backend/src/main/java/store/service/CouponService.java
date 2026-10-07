package store.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import store.config.StoreProperties;
import store.domain.Coupon;
import store.error.ApiException;
import store.error.ErrorCode;
import store.repository.CouponRepository;
import store.repository.OrderRepository;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reward coupons: every n-th successfully placed order earns one coupon worth x% off, created when an admin asks.
 * <p>
 * Milestones are numbered by the coupons already generated: coupon k rewards order k·n. Because generation runs
 * under the store write lock and reads "coupons generated so far" inside it, two admins asking at once can never
 * produce two coupons for the same milestone, and a backlog (orders 5 and 10 both reached) is paid out one
 * coupon per call, oldest milestone first.
 */
@Service
public class CouponService {

    // No 0/O or 1/I so codes survive being read aloud or retyped.
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    // 10 characters from a 32-letter alphabet = 50 random bits: not practically guessable.
    private static final int CODE_SUFFIX_LENGTH = 10;

    private final CouponRepository coupons;
    private final OrderRepository orders;
    private final StoreLock lock;
    private final Clock clock;
    private final int everyNthOrder;
    private final int percentOff;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public CouponService(CouponRepository coupons, OrderRepository orders, StoreLock lock, Clock clock,
                         StoreProperties properties) {
        this(coupons, orders, lock, clock, properties.rewards().n(), properties.rewards().x());
    }

    CouponService(CouponRepository coupons, OrderRepository orders, StoreLock lock, Clock clock, int everyNthOrder,
                  int percentOff) {
        this.coupons = coupons;
        this.orders = orders;
        this.lock = lock;
        this.clock = clock;
        this.everyNthOrder = everyNthOrder;
        this.percentOff = percentOff;
    }

    /** Generates the coupon for the oldest milestone that has been reached but not yet rewarded. */
    public Coupon generate() {
        return lock.write(() -> {
            long placedOrders = orders.count();
            long nextMilestone = (coupons.count() + 1) * everyNthOrder;
            if (placedOrders < nextMilestone) {
                throw new ApiException(ErrorCode.NO_ELIGIBLE_MILESTONE,
                        "No unrewarded milestone: the next coupon is earned at order #" + nextMilestone + "; "
                                + placedOrders + " order(s) placed so far",
                        Map.of("nextMilestone", nextMilestone, "placedOrders", placedOrders));
            }
            Coupon coupon = Coupon.issue(newCode(nextMilestone), percentOff, nextMilestone, Instant.now(clock));
            coupons.add(coupon);
            return coupon;
        });
    }

    public List<Coupon> list() {
        return lock.read(coupons::findAll);
    }

    /**
     * Finds a coupon that can still be used, or fails with COUPON_NOT_FOUND / COUPON_ALREADY_REDEEMED.
     * Caller holds the store lock (checkout does, so the coupon cannot be redeemed by someone else in between).
     */
    Coupon requireAvailable(String code) {
        Coupon coupon = coupons.findByCode(code)
                .orElseThrow(() -> new ApiException(ErrorCode.COUPON_NOT_FOUND, "Coupon not found: " + code,
                        Map.of("couponCode", code)));
        if (!coupon.isAvailable()) {
            throw new ApiException(ErrorCode.COUPON_ALREADY_REDEEMED, "Coupon " + code + " has already been used",
                    Map.of("couponCode", code));
        }
        return coupon;
    }

    /** Marks the coupon used by {@code orderId}. Caller holds the store lock and has checked availability. */
    void redeem(Coupon coupon, String orderId) {
        coupons.replace(coupon.redeem(orderId, Instant.now(clock)));
    }

    /** Codes are matched case-insensitively and without surrounding spaces; blank means "no coupon". */
    public static String normalize(String code) {
        return code == null || code.isBlank() ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    private String newCode(long milestone) {
        StringBuilder suffix = new StringBuilder(CODE_SUFFIX_LENGTH);
        for (int i = 0; i < CODE_SUFFIX_LENGTH; i++) {
            suffix.append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]);
        }
        // The milestone makes the code unique on its own; the random suffix makes it hard to guess.
        return String.format(Locale.ROOT, "REWARD-%04d-%s", milestone, suffix);
    }
}
