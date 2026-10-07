package store.web;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import store.error.ErrorCode;

import java.util.Map;

/**
 * Replaces Spring Boot's default /error endpoint, which answers with its own body shape
 * ({@code timestamp, status, error}). Errors that never reach a controller (and direct requests to /error)
 * now get the same {@code {code, message, details}} body as everything else.
 */
@RestController
public class ApiErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int code = status instanceof Integer value ? value : 404; // a direct GET /error is just an unknown path
        return switch (code) {
            case 400 -> ApiExceptionHandler.respond(ErrorCode.VALIDATION_ERROR, "Bad request", Map.of());
            case 404 -> ApiExceptionHandler.respond(ErrorCode.NOT_FOUND, "No endpoint matches this path", Map.of());
            case 405 -> ApiExceptionHandler.respond(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed", Map.of());
            default -> ApiExceptionHandler.respond(ErrorCode.INTERNAL_ERROR, "Unexpected server error", Map.of());
        };
    }
}
