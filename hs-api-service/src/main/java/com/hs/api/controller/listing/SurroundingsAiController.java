package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.listing.service.surroundings.PoiGate;
import com.hs.listing.service.surroundings.SurroundingsDtos.Request;
import com.hs.listing.service.surroundings.SurroundingsDtos.Response;
import com.hs.listing.service.surroundings.SurroundingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nearby-amenity comparison for the AI assistant. Mapped as {@code /listings/ai/surroundings/**}: the Gateway
 * strips {@code /api/v1}; only GET /listings/** is public, so this POST always requires a signed-in user.
 * The caller supplies no provider category, URL or coordinate: Core reads coordinates from listing_addresses.
 */
@RestController
@RequestMapping({"/listings/ai/surroundings", "/api/v1/listings/ai/surroundings"})
@RequiredArgsConstructor
public class SurroundingsAiController {
    private final SurroundingsService service;
    private final PoiGate gate;
    @Value("${geoapify.user-limit.requests:10}")
    private int userLimit;

    @PostMapping("/compare")
    public ApiResponse<Response> compare(@RequestBody Request request) {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        if (!gate.tryAcquireUser(context.userId(), userLimit)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many amenity comparisons; try again shortly");
        }
        try {
            return ApiResponse.<Response>builder().result(service.compare(request)).build();
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getMessage());
        }
    }
}
