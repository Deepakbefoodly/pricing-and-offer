package store.web;

import store.error.ErrorCode;

import java.util.Map;

/** The single error body shape used by every endpoint. */
public record ApiError(ErrorCode code, String message, Map<String, Object> details) {
}
