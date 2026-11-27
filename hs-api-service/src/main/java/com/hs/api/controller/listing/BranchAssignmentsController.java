package com.hs.api.controller.listing;

import com.hs.api.profile.RequiresCompleteProfile;
import com.hs.common.advice.entity.AppException;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.branch.BranchAssignmentsResponse;
import com.hs.contract.dto.branch.BranchCandidatesResponse;
import com.hs.contract.dto.branch.BranchContractLinkResponse;
import com.hs.contract.service.BranchAssignmentService;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.constant.RoomManualStatus;
import com.hs.listing.service.BranchRoomLinkService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Arrays;
import java.util.Map;

@RestController
@RequestMapping("/branches/{branchId}")
@RequiredArgsConstructor
public class BranchAssignmentsController {

    private final BranchAssignmentService assignmentService;
    private final BranchRoomLinkService roomLinkService;

    public record RoomListingLink(String listingId, String roomId) {}

    public record RoomStatus(String roomId, RoomManualStatus manualStatus) {}

    @GetMapping("/assignments")
    public ApiResponse<BranchAssignmentsResponse> assignments(@PathVariable String branchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(assignmentService.assignments(branchId, CurrentUser.requireUserId(), page, size));
    }

    @GetMapping("/assignment-candidates")
    public ApiResponse<BranchCandidatesResponse> candidates(@PathVariable String branchId, @RequestParam String kind,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(assignmentService.candidates(branchId, CurrentUser.requireUserId(), kind, page, size));
    }

    @RequiresCompleteProfile
    @PreAuthorize("hasAuthority('LISTING_POST')")
    @PutMapping("/rooms/{roomId}/listings/{listingId}")
    public ApiResponse<RoomListingLink> linkListing(@PathVariable String branchId, @PathVariable String roomId,
            @PathVariable String listingId) {
        roomLinkService.link(branchId, CurrentUser.requireUserId(), roomId, listingId);
        return ok(new RoomListingLink(listingId, roomId));
    }

    @RequiresCompleteProfile
    @PreAuthorize("hasAuthority('LISTING_POST')")
    @DeleteMapping("/rooms/{roomId}/listings/{listingId}")
    public ApiResponse<Void> unlinkListing(@PathVariable String branchId, @PathVariable String roomId,
            @PathVariable String listingId) {
        roomLinkService.unlink(branchId, CurrentUser.requireUserId(), roomId, listingId);
        return ok(null);
    }

    // Adding a contract to the tray preserves a room saved by another request or hidden by a schedule conflict.
    @RequiresCompleteProfile
    @PreAuthorize("hasAuthority('LISTING_POST')")
    @PutMapping("/contracts/{contractId}")
    public ApiResponse<BranchContractLinkResponse> attachContract(@PathVariable String branchId,
            @PathVariable String contractId, @RequestBody Map<String, Object> body) {
        String userId = CurrentUser.requireUserId();
        if (body.containsKey("addToTray")) {
            if (Boolean.TRUE.equals(body.get("addToTray")) && !body.containsKey("roomId")) {
                return ok(assignmentService.addToTray(branchId, userId, contractId));
            }
            throw new AppException(ListingErrorCode.BRANCH_ASSIGNMENT_INVALID);
        }
        Object roomId = body.get("roomId");
        if (!body.containsKey("roomId") || roomId != null && !(roomId instanceof String text && !text.isBlank())) {
            throw new AppException(ListingErrorCode.BRANCH_ASSIGNMENT_INVALID, "Cần gửi roomId là mã phòng hoặc null");
        }
        return ok(assignmentService.attach(branchId, userId, contractId, (String) roomId));
    }

    @RequiresCompleteProfile
    @PreAuthorize("hasAuthority('LISTING_POST')")
    @DeleteMapping("/contracts/{contractId}")
    public ApiResponse<Void> detachContract(@PathVariable String branchId, @PathVariable String contractId) {
        assignmentService.detach(branchId, CurrentUser.requireUserId(), contractId);
        return ok(null);
    }

    @RequiresCompleteProfile
    @PreAuthorize("hasAuthority('LISTING_POST')")
    @PutMapping("/rooms/{roomId}/manual-status")
    public ApiResponse<RoomStatus> setManualStatus(@PathVariable String branchId, @PathVariable String roomId,
            @RequestBody Map<String, Object> body) {
        String userId = CurrentUser.requireUserId();
        RoomManualStatus status = Arrays.stream(RoomManualStatus.values())
                .filter(s -> s.name().equals(body.get("status")))
                .findFirst()
                .orElseThrow(() -> new AppException(ListingErrorCode.BRANCH_ASSIGNMENT_INVALID,
                        "status phải là NONE, EXTERNAL_RENT hoặc REPAIR"));
        return ok(new RoomStatus(roomId, roomLinkService.setManualStatus(branchId, userId, roomId, status)));
    }

    // Without this, malformed JSON and missing or mistyped parameters reach the global catch-all and answer 500.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception) {
        ListingErrorCode error = ListingErrorCode.BRANCH_ASSIGNMENT_INVALID;
        return ResponseEntity.status(error.getStatusCode())
                .body(ApiResponse.<Void>builder().code(error.getCode()).message(error.getMessage()).build());
    }

    private <T> ApiResponse<T> ok(T result) {
        return ApiResponse.<T>builder().result(result).build();
    }
}
