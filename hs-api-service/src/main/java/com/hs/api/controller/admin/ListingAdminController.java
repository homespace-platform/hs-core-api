package com.hs.api.controller.admin;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.*;
import com.hs.listing.dto.response.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.service.ListingAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/admin/listings")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAuthority('ADMIN')")
public class ListingAdminController {
    private final ListingAdminService adminService;

    @GetMapping
    public PageResponse<AdminListingSummaryResponse> findAll(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) ListingStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) ListingCategory category,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(defaultValue = "submittedAt,desc") String sort) {
        return adminService.findAll(page, size, status, keyword, ownerId, category, fromDate, toDate, sort);
    }

    @GetMapping("/{listingId}")
    public ApiResponse<AdminListingDetailResponse> getById(@PathVariable String listingId) {
        return ApiResponse.<AdminListingDetailResponse>builder()
                .result(adminService.getById(listingId))
                .build();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateListingResponse> create(@RequestBody @Valid AdminCreateListingRequest request) {
        return ApiResponse.<CreateListingResponse>builder()
                .message("Listing created")
                .result(adminService.create(currentUserId(), request))
                .build();
    }

    @PutMapping("/{listingId}")
    public ApiResponse<CreateListingResponse> update(
            @PathVariable String listingId,
            @RequestBody @Valid CreateListingRequest request) {
        return ApiResponse.<CreateListingResponse>builder()
                .message("Listing updated")
                .result(adminService.update(currentUserId(), listingId, request))
                .build();
    }

    @PatchMapping("/{listingId}/status")
    public ApiResponse<ListingDetailResponse> changeStatus(
            @PathVariable String listingId,
            @RequestBody @Valid ChangeListingStatusRequest request) {
        return ApiResponse.<ListingDetailResponse>builder()
                .message("Listing status updated")
                .result(adminService.changeStatus(currentUserId(), listingId, request.status(), request.reason()))
                .build();
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.userId();
    }
}
