package store.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Injected rather than calling Instant.now() directly, so tests can pin time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
