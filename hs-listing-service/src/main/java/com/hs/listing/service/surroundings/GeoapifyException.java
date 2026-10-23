package com.hs.listing.service.surroundings;

/** Controlled provider failure; the message never contains the API key or the request URL. */
public class GeoapifyException extends RuntimeException {
    public enum Code {
        GEOAPIFY_NOT_CONFIGURED, UPSTREAM_ERROR, UPSTREAM_AUTH, UPSTREAM_RATE_LIMITED, INVALID_RESPONSE, BUDGET_EXHAUSTED
    }

    private final Code code;
    private final Integer retryAfterSeconds;

    public GeoapifyException(Code code, String message) { this(code, message, null); }

    public GeoapifyException(Code code, String message, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Code code() { return code; }
    public Integer retryAfterSeconds() { return retryAfterSeconds; }
}
