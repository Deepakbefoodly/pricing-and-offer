package store.web;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import store.error.ApiException;
import store.error.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Translates every failure into the {@link ApiError} shape so clients never see an HTML or ad-hoc body. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException e) {
        ResponseEntity<ApiError> response = respond(e.getCode(), e.getMessage(), e.getDetails());
        if (e.getDetails().get("retryAfterSeconds") instanceof Long seconds) {
            // Standard header for 429, so generic HTTP clients back off without parsing the body.
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders())
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds)).body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleInvalidBody(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return respond(ErrorCode.VALIDATION_ERROR, "Request body failed validation", Map.of("fields", fields));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException e) {
        // Name the offending field when JSON parsed but did not bind (wrong type, unknown property).
        if (e.getCause() instanceof UnrecognizedPropertyException unknown) {
            return respond(ErrorCode.VALIDATION_ERROR, "Request body has an unknown field",
                    Map.of("fields", Map.of(fieldPath(unknown), "unknown field")));
        }
        if (e.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            return respond(ErrorCode.VALIDATION_ERROR, "Request body has a field of the wrong type",
                    Map.of("fields", Map.of(fieldPath(mismatch), "wrong type")));
        }
        return respond(ErrorCode.VALIDATION_ERROR, "Request body is missing or malformed", Map.of());
    }

    private static String fieldPath(JsonMappingException e) {
        return e.getPath().stream()
                .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                .collect(Collectors.joining("."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Invalid value for '" + e.getName() + "'", Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException e) {
        return respond(ErrorCode.VALIDATION_ERROR, "Missing required header '" + e.getHeaderName() + "'", Map.of());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> handleUnknownRoute(Exception e) {
        return respond(ErrorCode.NOT_FOUND, "No endpoint matches this path", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, "Method " + e.getMethod() + " is not supported for this path", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        return respond(ErrorCode.NOT_ACCEPTABLE, "This API only produces application/json", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return respond(ErrorCode.INTERNAL_ERROR, "Unexpected server error", Map.of());
    }

    /**
     * The content type is fixed to JSON rather than negotiated: otherwise a client sending e.g.
     * {@code Accept: application/xml} turns every error into a failed error handler and a 500.
     */
    static ResponseEntity<ApiError> respond(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(code, message, details));
    }
}
