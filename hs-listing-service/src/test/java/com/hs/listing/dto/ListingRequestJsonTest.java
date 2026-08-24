package com.hs.listing.dto;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hs.listing.dto.request.UpdateListingRequest;

import tools.jackson.databind.ObjectMapper;

class ListingRequestJsonTest {

    @Test
    void deserializesDetailsAsJsonNode() {
        UpdateListingRequest request = new ObjectMapper().readValue(
                "{\"details\":{\"furnished\":true}}",
                UpdateListingRequest.class);

        assertTrue(request.details().path("furnished").asBoolean());
    }
}
