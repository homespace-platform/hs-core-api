package com.hs.api.controller.listing;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.listing.dto.request.CreatePropertyBranchRequest;
import com.hs.listing.dto.response.PropertyBranchResponse;
import com.hs.listing.service.PropertyBranchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/branches")
@RequiredArgsConstructor
@Validated
public class PropertyBranchController {

    private final PropertyBranchService branchService;

    @PostMapping
    public ResponseEntity<ApiResponse<PropertyBranchResponse>> createBranch(
            @RequestBody @Valid CreatePropertyBranchRequest request) {
        String userId = currentUserId();
        var body = ApiResponse.<PropertyBranchResponse>builder()
                .message("Tạo chi nhánh thành công")
                .result(branchService.createBranch(userId, request))
                .build();
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    public ApiResponse<List<PropertyBranchResponse>> getMyBranches() {
        return ApiResponse.<List<PropertyBranchResponse>>builder()
                .result(branchService.getMyBranches(currentUserId()))
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<PropertyBranchResponse> getBranchById(@PathVariable String id) {
        return ApiResponse.<PropertyBranchResponse>builder()
                .result(branchService.getBranchById(id, currentUserId()))
                .build();
    }

    @PutMapping("/{id}")
    public ApiResponse<PropertyBranchResponse> updateBranch(
            @PathVariable String id,
            @RequestBody @Valid CreatePropertyBranchRequest request) {
        return ApiResponse.<PropertyBranchResponse>builder()
                .message("Cập nhật chi nhánh thành công")
                .result(branchService.updateBranch(id, currentUserId(), request))
                .build();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteBranch(@PathVariable String id) {
        branchService.deleteBranch(id, currentUserId());
        return ApiResponse.<Void>builder()
                .message("Xóa chi nhánh thành công")
                .build();
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        return context == null ? null : context.userId();
    }
}
