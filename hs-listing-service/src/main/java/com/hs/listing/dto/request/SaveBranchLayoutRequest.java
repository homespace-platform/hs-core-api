package com.hs.listing.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * Whole floor-map tree; array order is the position. A node without id is new. Validated by BranchLayoutService.
 * Scalars are strict on this request only: Jackson's default coercion would save wrong types, and a missing
 * roomCode would silently turn a room into an aisle.
 */
public record SaveBranchLayoutRequest(
        @JsonDeserialize(using = StrictLong.class) Long expectedRevision,
        List<Floor> floors) {

    public record Floor(
            @JsonDeserialize(using = StrictString.class) String id,
            @JsonDeserialize(using = StrictString.class) String label,
            List<Row> rows) {
    }

    public record Row(
            @JsonDeserialize(using = StrictString.class) String id,
            @JsonDeserialize(using = StrictString.class) String label,
            List<Slot> slots) {
    }

    public record Slot(
            @JsonDeserialize(using = StrictString.class) String id,
            @JsonProperty(required = true) @JsonDeserialize(using = StrictString.class) String roomCode) {
    }

    // Explicit JSON null never reaches deserialize(), so it stays null.
    static final class StrictString extends ValueDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "Expected a JSON string");
            }
            return parser.getString();
        }
    }

    static final class StrictLong extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Long.class, "Expected a JSON integer");
            }
            return parser.getLongValue();
        }
    }
}
