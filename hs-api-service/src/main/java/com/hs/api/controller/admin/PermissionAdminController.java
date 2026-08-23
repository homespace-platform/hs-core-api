package com.hs.api.controller.admin;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.user.dto.request.UpdatePermissionRequest;
import com.hs.user.dto.response.PermissionResponse;
import com.hs.user.service.PermissionService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequestMapping("/admin/permissions")
@PreAuthorize("hasAuthority('ADMIN')")
public class PermissionAdminController {

        PermissionService permissionService;

        @GetMapping()
        public ApiResponse<PageResponse<PermissionResponse>> findAllPermissions(
                        @PageableDefault(value = 10) Pageable pageable) {
                PageResponse<PermissionResponse> page = new PageResponse<>(
                                permissionService.findAllPermissions(pageable));

                return ApiResponse.<PageResponse<PermissionResponse>>builder()
                                .result(page)
                                .build();
        }

        @GetMapping("/all")
        public ApiResponse<List<PermissionResponse>> findAllPermissions() {
                return ApiResponse.<List<PermissionResponse>>builder()
                                .result(permissionService.findAllPermissions())
                                .build();
        }

        @PostMapping("/{id}")
        public ApiResponse<@NonNull Void> updatePermission(
                        @RequestBody @Valid UpdatePermissionRequest updatePermissionRequest,
                        @PathVariable("id") String id) {
                permissionService.updatePermission(id, updatePermissionRequest);
                return ApiResponse.<Void>builder().build();
        }

        @DeleteMapping("/{id}")
        public ApiResponse<@NonNull Void> deletePermission(
                        @PathVariable("id") String id) {
                permissionService.deletePermissionById(id);
                return ApiResponse.<Void>builder().build();
        }

}


