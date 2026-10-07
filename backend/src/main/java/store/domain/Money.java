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

    private static final Pattern FORMAT = Pattern.compile("\\d{1,7}(\\.\\d{1,2})?");

    private Money() {
    }

    /** Parses a client-supplied amount such as "27.5" or "27.50"; up to 9,999,999.99. */
    public static BigDecimal parse(String text, String field) {
        if (text == null || !FORMAT.matcher(text).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Invalid amount for '" + field + "'",
                    Map.of("fields", Map.of(field, "must be a decimal string with at most 2 decimals, e.g. \"27.50\"")));
        }
        return new BigDecimal(text).setScale(SCALE);
    }

    public static String format(BigDecimal amount) {
        return amount.setScale(SCALE).toPlainString();
    }
}
