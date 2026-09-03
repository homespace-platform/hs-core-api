package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.CreateRentalRequest;
import com.hs.listing.dto.request.RejectRentalRequest;
import com.hs.listing.dto.response.RentalRequestResponse;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.service.RentalRequestService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/rental-requests")
@RequiredArgsConstructor
@Validated
public class RentalRequestController {

    private final RentalRequestService rentalRequestService;

    // =========================================================================
    // 1. DÀNH CHO KHÁCH HÀNG (RENTER)
    // =========================================================================

    @PostMapping
    public ApiResponse<RentalRequestResponse> createRentalRequest(@RequestBody @Valid CreateRentalRequest req) {
        UserContext context = requireUserContext();
        return ApiResponse.<RentalRequestResponse>builder()
                .message("Gửi yêu cầu thuê nhà thành công. Vui lòng chờ chủ nhà phản hồi.")
                .result(rentalRequestService.createRentalRequest(context.userId(), context.email(), req))
                .build();
    }

    @GetMapping("/my-requests")
    public PageResponse<RentalRequestResponse> getMyRequests(
            @RequestParam(required = false) RentalRequestStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        String renterId = requireUserId();
        return rentalRequestService.getMyRequests(renterId, status, page, size);
    }

    @GetMapping("/by-listing/{listingId}")
    public ApiResponse<RentalRequestResponse> getMyRequestByListing(@PathVariable String listingId) {
        String currentUserId = optionalUserId();
        if (currentUserId == null) {
            return ApiResponse.<RentalRequestResponse>builder().result(null).build();
        }
        return ApiResponse.<RentalRequestResponse>builder()
                .result(rentalRequestService.getMyRequestByListing(currentUserId, listingId))
                .build();
    }

    @PutMapping("/{id}/cancel")
    public ApiResponse<RentalRequestResponse> cancelRentalRequest(@PathVariable String id) {
        String renterId = requireUserId();
        return ApiResponse.<RentalRequestResponse>builder()
                .message("Hủy yêu cầu thuê nhà thành công.")
                .result(rentalRequestService.cancelRentalRequest(renterId, id))
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<RentalRequestResponse> getRequestById(@PathVariable String id) {
        String actorId = requireUserId();
        return ApiResponse.<RentalRequestResponse>builder()
                .result(rentalRequestService.getRequestById(id, actorId))
                .build();
    }

    // =========================================================================
    // 2. DÀNH CHO CHỦ NHÀ (OWNER / LANDLORD)
    // =========================================================================

    @GetMapping("/owner")
    public PageResponse<RentalRequestResponse> getOwnerRequests(
            @RequestParam(required = false) String listingId,
            @RequestParam(required = false) RentalRequestStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        String ownerId = requireUserId();
        return rentalRequestService.getOwnerRequests(ownerId, listingId, status, page, size);
    }

    @PutMapping("/{id}/accept")
    public ApiResponse<RentalRequestResponse> acceptRentalRequest(@PathVariable String id) {
        String ownerId = requireUserId();
        return ApiResponse.<RentalRequestResponse>builder()
                .message("Chấp thuận yêu cầu thuê thành công. Bất động sản chuyển sang trạng thái giữ chỗ trong 24 giờ.")
                .result(rentalRequestService.acceptRentalRequest(ownerId, id))
                .build();
    }

    @PutMapping("/{id}/reject")
    public ApiResponse<RentalRequestResponse> rejectRentalRequest(
            @PathVariable String id,
            @RequestBody(required = false) @Valid RejectRentalRequest req) {
        String ownerId = requireUserId();
        return ApiResponse.<RentalRequestResponse>builder()
                .message("Từ chối yêu cầu thuê thành công.")
                .result(rentalRequestService.rejectRentalRequest(ownerId, id, req))
                .build();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private UserContext requireUserContext() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return context;
    }

    private String requireUserId() {
        return requireUserContext().userId();
    }

    private String optionalUserId() {
        UserContext context = UserContextHolder.get();
        return (context != null && context.userId() != null && !context.userId().isBlank())
                ? context.userId() : null;
    }
}
