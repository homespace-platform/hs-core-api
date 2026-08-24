package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.request.AddListingImageRequest;
import com.hs.listing.dto.request.UpdateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.listing.service.ListingService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.web.PageableDefault;

@RestController
@RequestMapping("/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @GetMapping
    public PageResponse<ListingResponse> getAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return listingService.getAll(pageable);
    }

    @GetMapping("/{listingId}")
    public ApiResponse<ListingResponse> getById(@PathVariable String listingId) {
        return ApiResponse.<ListingResponse>builder()
                .result(listingService.getById(currentUserId(), listingId))
                .build();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ListingResponse> createDraft(@RequestBody @Valid CreateListingRequest request) {
        UserContext context = UserContextHolder.get();
        String ownerId = context == null ? null : context.userId();
        return ApiResponse.<ListingResponse>builder()
                .message("Listing draft created")
                .result(listingService.createDraft(ownerId, request))
                .build();
    }

    @PatchMapping("/{listingId}")
    public ApiResponse<ListingResponse> updateDraft(
            @PathVariable String listingId,
            @RequestBody @Valid UpdateListingRequest request) {
        return ApiResponse.<ListingResponse>builder()
                .message("Listing draft updated")
                .result(listingService.updateDraft(currentUserId(), listingId, request))
                .build();
    }

    @PostMapping("/{listingId}/images")
    public ApiResponse<ListingResponse> addImage(
            @PathVariable String listingId,
            @RequestBody @Valid AddListingImageRequest request) {
        return ApiResponse.<ListingResponse>builder()
                .message("Listing image attached")
                .result(listingService.addImage(currentUserId(), listingId, request))
                .build();
    }

    @PostMapping("/{listingId}/publish")
    public ApiResponse<ListingResponse> publish(@PathVariable String listingId) {
        return ApiResponse.<ListingResponse>builder()
                .message("Listing published")
                .result(listingService.publish(currentUserId(), listingId))
                .build();
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.userId();
    }

}
