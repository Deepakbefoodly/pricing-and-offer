package store.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Application settings bound from the {@code store.*} keys in application.yml.
 * Validated at startup so a misconfigured reward rule fails fast instead of at the first checkout.
 */
@Validated
@ConfigurationProperties(prefix = "store")
public record StoreProperties(@Valid @NotNull Rewards rewards, @Valid @NotNull Cors cors) {

    /**
     * @param n every n-th successfully placed order unlocks one coupon
     * @param x coupon discount, in whole percent
     */
    public record Rewards(@Min(1) int n, @Min(1) @Max(100) int x) {
    }

    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }
}
