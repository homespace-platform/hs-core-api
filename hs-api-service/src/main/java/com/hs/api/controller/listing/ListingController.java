package com.hs.api.controller.listing;

import com.hs.common.context.*;
import com.hs.common.dto.ApiResponse;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.response.CreateListingResponse;
import com.hs.listing.service.ListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ListingController {
    private final ListingService listingService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateListingResponse> create(@RequestBody @Valid CreateListingRequest request) {
        UserContext c = UserContextHolder.get();
        return ApiResponse.<CreateListingResponse>builder().message("Listing created")
                .result(listingService.create(c == null ? null : c.userId(), request)).build();
    }
}
