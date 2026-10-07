package store.error;

import java.util.Map;

/**
 * Domain failure that maps directly to an API error response.
 * Services throw it; {@link store.web.ApiExceptionHandler} turns it into JSON.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public ErrorCode getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
