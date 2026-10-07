package store.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

@Configuration
public class WebConfig {

    /**
     * CORS as a servlet filter rather than an MVC mapping: MVC only decorates requests that reach a handler,
     * so error responses (unknown routes, failures before dispatch) would lack CORS headers and the browser
     * would hide the JSON error body from the frontend.
     */
    @Bean
    FilterRegistrationBean<CorsFilter> corsFilter(StoreProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(properties.cors().allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key"));
        // Browsers hide non-safelisted response headers unless they are exposed explicitly.
        cors.setExposedHeaders(List.of("Location", "Idempotent-Replayed"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
