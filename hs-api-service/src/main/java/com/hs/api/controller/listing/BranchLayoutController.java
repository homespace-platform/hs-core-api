package com.hs.api.controller.listing;

import com.hs.api.profile.RequiresCompleteProfile;
import com.hs.common.context.CurrentUser;
import com.hs.common.dto.ApiResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.SaveBranchLayoutRequest;
import com.hs.listing.dto.response.BranchLayoutResponse;
import com.hs.listing.service.BranchLayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/branches/{branchId}/layout")
@RequiredArgsConstructor
public class BranchLayoutController {

    private final BranchLayoutService layoutService;

    @GetMapping
    public ApiResponse<BranchLayoutResponse> getLayout(@PathVariable String branchId) {
        return ApiResponse.<BranchLayoutResponse>builder()
                .result(layoutService.getLayout(branchId, CurrentUser.requireUserId()))
                .build();
    }

    @RequiresCompleteProfile
    @PreAuthorize("hasAuthority('LISTING_POST')")
    @PutMapping
    public ApiResponse<BranchLayoutResponse> saveLayout(@PathVariable String branchId,
                                                        @RequestBody SaveBranchLayoutRequest request) {
        return ApiResponse.<BranchLayoutResponse>builder()
                .result(layoutService.saveLayout(branchId, CurrentUser.requireUserId(), request))
                .build();
    }

    // Without this, malformed JSON or a wrong JSON type reaches the global catch-all and answers 500.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadableBody(HttpMessageNotReadableException exception) {
        ListingErrorCode error = ListingErrorCode.BRANCH_LAYOUT_INVALID;
        return ResponseEntity.status(error.getStatusCode())
                .body(ApiResponse.<Void>builder().code(error.getCode()).message(error.getMessage()).build());
    }
}
