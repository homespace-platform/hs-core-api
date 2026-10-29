package com.hs.listing.service;

import java.util.List;

/** A rejected AI search predicate; never silently converted to "no results". */
public class AiSearchValidationException extends IllegalArgumentException {
    private final String reason;
    private final String field;
    private final List<String> allowed;

    public AiSearchValidationException(String reason, String field, List<String> allowed, String message) {
        super(message);
        this.reason = reason;
        this.field = field;
        this.allowed = allowed == null ? List.of() : List.copyOf(allowed);
    }

    public AiSearchValidationException(String reason, String field, String message) {
        this(reason, field, List.of(), message);
    }

    public String reason() { return reason; }
    public String field() { return field; }
    public List<String> allowed() { return allowed; }
}
