package store.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import store.error.ApiException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void parsesToScaleTwo() {
        assertThat(Money.parse("27.5", "amount")).isEqualTo("27.50");
        assertThat(Money.parse("0", "amount")).isEqualTo("0.00");
    }

    @Test
    void acceptsTotalsFarAboveAnySinglePrice() {
        // A cart of two 9,999,999.99 items must still be payable.
        assertThat(Money.parse("19999999.98", "amount")).isEqualTo("19999999.98");
        assertThat(Money.parse(Money.MAX_AMOUNT.toPlainString(), "amount")).isEqualTo(Money.MAX_AMOUNT);
    }

    /** Each kind of bad input gets a message that names the actual problem. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "abc                   | must be a decimal string such as \"27.50\"",
            "1e3                   | must be a decimal string such as \"27.50\"",
            "-5.00                 | must be a decimal string such as \"27.50\"",
            "10.                   | must be a decimal string such as \"27.50\"",
            "19.999                | must have at most 2 decimals",
            "1000000000000000.00   | must not exceed 999999999999999.99",
    })
    void rejectsWithASpecificReason(String input, String reason) {
        assertThatThrownBy(() -> Money.parse(input, "amount"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getDetails().get("fields")).isEqualTo(Map.of("amount", reason)));
    }
}
