package store.web;

import org.junit.jupiter.api.Test;
import store.error.ApiException;
import store.error.ErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CouponGuessLimiterTest {

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private final CouponGuessLimiter limiter = new CouponGuessLimiter(3, Duration.ofMinutes(15), clock);

    @Test
    void blocksAClientAfterTooManyFailuresUntilTheWindowPasses() {
        for (int i = 0; i < 3; i++) {
            limiter.requireAllowed("10.0.0.1");
            limiter.recordFailure("10.0.0.1");
            clock.advance(Duration.ofMinutes(1));
        }

        assertThatThrownBy(() -> limiter.requireAllowed("10.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.RATE_LIMITED);
                    // the oldest failure (3 minutes ago) expires 12 minutes from now
                    assertThat(e.getDetails()).containsEntry("retryAfterSeconds", 12 * 60L);
                });

        clock.advance(Duration.ofMinutes(12)); // oldest failure falls out of the window
        assertThatCode(() -> limiter.requireAllowed("10.0.0.1")).doesNotThrowAnyException();
    }

    @Test
    void clientsAreCountedSeparately() {
        for (int i = 0; i < 3; i++) {
            limiter.recordFailure("10.0.0.1");
        }
        assertThatCode(() -> limiter.requireAllowed("10.0.0.2")).doesNotThrowAnyException();
    }
}
