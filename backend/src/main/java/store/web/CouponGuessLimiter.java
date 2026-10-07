package store.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import store.config.StoreProperties;
import store.error.ApiException;
import store.error.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down coupon-code guessing: each client (by IP address) may try a limited number of unknown codes per
 * time window; after that its checkouts *with a coupon* are refused until the window has passed. Checkouts
 * without a coupon are never affected, so legitimate high-volume traffic from one address is not throttled.
 * <p>
 * This complements, not replaces, unguessable codes: a distributed attacker has many addresses. Behind a
 * proxy the client address must come from a trusted forwarded header (server.forward-headers-strategy).
 */
@Component
public class CouponGuessLimiter {

    private final int maxFailedAttempts;
    private final Duration window;
    private final Clock clock;
    // Per client, the times of its recent failed attempts (at most maxFailedAttempts, oldest first).
    private final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();

    @Autowired
    public CouponGuessLimiter(StoreProperties properties, Clock clock) {
        this(properties.couponGuessing().maxFailedAttempts(), properties.couponGuessing().window(), clock);
    }

    CouponGuessLimiter(int maxFailedAttempts, Duration window, Clock clock) {
        this.maxFailedAttempts = maxFailedAttempts;
        this.window = window;
        this.clock = clock;
    }

    /** Fails with RATE_LIMITED if {@code client} has used up its failed attempts in the current window. */
    public void requireAllowed(String client) {
        Instant now = clock.instant();
        List<Instant> recent = failures.computeIfPresent(client, (key, times) -> {
            List<Instant> kept = withinWindow(times, now);
            return kept.isEmpty() ? null : kept; // forget clients whose failures have all expired
        });
        if (recent != null && recent.size() >= maxFailedAttempts) {
            long retryAfter = Math.max(1, Duration.between(now, recent.get(0).plus(window)).toSeconds());
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "Too many unknown coupon codes; try again in " + retryAfter + " seconds",
                    Map.of("retryAfterSeconds", retryAfter));
        }
    }

    public void recordFailure(String client) {
        Instant now = clock.instant();
        failures.compute(client, (key, times) -> {
            List<Instant> kept = new ArrayList<>(times == null ? List.of() : withinWindow(times, now));
            kept.add(now);
            // Only the newest maxFailedAttempts matter, which also bounds memory per client.
            return List.copyOf(kept.subList(Math.max(0, kept.size() - maxFailedAttempts), kept.size()));
        });
    }

    private List<Instant> withinWindow(List<Instant> times, Instant now) {
        Instant cutoff = now.minus(window);
        return times.stream().filter(time -> time.isAfter(cutoff)).toList();
    }
}
