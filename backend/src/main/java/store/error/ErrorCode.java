package store.error;

import org.springframework.http.HttpStatus;

/**
 * Stable, machine-readable error codes returned in every error body.
 * Clients branch on {@code code}; the HTTP status only groups them.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST),

    PAYMENT_FAILED(HttpStatus.PAYMENT_REQUIRED),

    NOT_FOUND(HttpStatus.NOT_FOUND),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND),
    CART_NOT_FOUND(HttpStatus.NOT_FOUND),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND),

    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    PRODUCT_MODIFIED(HttpStatus.CONFLICT),
    CART_NOT_OPEN(HttpStatus.CONFLICT),
    CART_ALREADY_CHECKED_OUT(HttpStatus.CONFLICT),
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    PRICE_CHANGED(HttpStatus.CONFLICT),
    COUPON_ALREADY_REDEEMED(HttpStatus.CONFLICT),
    NO_ELIGIBLE_MILESTONE(HttpStatus.CONFLICT),

    CART_EMPTY(HttpStatus.UNPROCESSABLE_ENTITY),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),

    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
