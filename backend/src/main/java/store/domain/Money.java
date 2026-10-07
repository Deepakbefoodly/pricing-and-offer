package store.domain;

import store.error.ApiException;
import store.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Money rules: amounts are {@link BigDecimal} at scale 2 and cross the API as plain strings ("25.00").
 * Input with more than two decimals is rejected rather than rounded, so nothing is silently changed.
 */
public final class Money {

    public static final int SCALE = 2;

    /**
     * Upper bound for any amount the API accepts. Far above any reachable cart total (a single price is capped
     * lower by the catalogue), so a cart the store let you build can always be checked out.
     */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.99");

    private static final Pattern DECIMAL = Pattern.compile("\\d{1,20}(\\.\\d{1,20})?");

    private Money() {
    }

    /** Parses a client-supplied amount such as "27.5" or "27.50", up to {@link #MAX_AMOUNT}. */
    public static BigDecimal parse(String text, String field) {
        if (text == null || !DECIMAL.matcher(text).matches()) {
            throw invalid(field, "must be a decimal string such as \"27.50\"");
        }
        BigDecimal amount = new BigDecimal(text);
        if (amount.scale() > SCALE) {
            throw invalid(field, "must have at most " + SCALE + " decimals");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw invalid(field, "must not exceed " + MAX_AMOUNT.toPlainString());
        }
        return amount.setScale(SCALE);
    }

    public static String format(BigDecimal amount) {
        return amount.setScale(SCALE).toPlainString();
    }

    private static ApiException invalid(String field, String reason) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid amount for '" + field + "'",
                Map.of("fields", Map.of(field, reason)));
    }
}
