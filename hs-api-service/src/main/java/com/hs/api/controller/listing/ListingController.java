package com.hs.api.controller.listing;

import com.hs.common.context.*;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.response.CreateListingResponse;
import com.hs.listing.dto.response.ListingDetailResponse;
import com.hs.listing.dto.response.MyListingSummaryResponse;
import com.hs.listing.service.ListingQueryService;
import com.hs.listing.service.ListingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
@Validated
public class ListingController {
    private final ListingService listingService;
    private final ListingQueryService listingQueryService;

    @PostMapping({"", "/upsert"})
    public ResponseEntity<ApiResponse<CreateListingResponse>> upsert(
            @RequestBody @Valid CreateListingRequest request) {
        boolean updating = request.id() != null && !request.id().isBlank();
        var body = ApiResponse.<CreateListingResponse>builder()
                .message(updating ? "Listing updated" : "Listing created")
                .result(listingService.upsert(currentUserId(), request))
                .build();
        return ResponseEntity.status(updating ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    @GetMapping("/me")
    public PageResponse<MyListingSummaryResponse> getMyListings(
            @RequestParam(defaultValue = "1") @Min(1) int page) {
        return listingQueryService.getMyListings(currentUserId(), page);
    }

    @GetMapping("/{listingId}")
    public ApiResponse<ListingDetailResponse> getById(@PathVariable String listingId) {
        return ApiResponse.<ListingDetailResponse>builder()
                .result(listingQueryService.getById(currentUserId(), listingId))
                .build();
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.userId();
    }
}
