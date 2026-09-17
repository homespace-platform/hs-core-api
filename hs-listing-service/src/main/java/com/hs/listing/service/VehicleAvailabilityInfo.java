package com.hs.listing.service;

public record VehicleAvailabilityInfo(
        int capacity,
        int reserved,
        int available
) {}
