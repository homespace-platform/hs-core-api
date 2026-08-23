package com.hs.api.controller.admin;

import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.user.dto.request.UpdateRoleRequest;
import com.hs.user.dto.response.RoleResponse;
import com.hs.user.service.RoleService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@RequestMapping("/admin/roles")
@PreAuthorize("hasAuthority('ADMIN')")
public class RoleAdminController {

    RoleService roleService;

    @GetMapping()
    public ApiResponse<PageResponse<RoleResponse>> findAllRoles(
            @PageableDefault(value = 10) Pageable pageable,
            @RequestParam(defaultValue = "false") boolean includePermissions) {
        PageResponse<RoleResponse> page = new PageResponse<>(
                roleService.findAllRoles(pageable, includePermissions));

        return ApiResponse.<PageResponse<RoleResponse>>builder()
                .result(page)
                .build();
    }

    @GetMapping("/all")
    public ApiResponse<List<RoleResponse>> findAllRoles() {
        return ApiResponse.<List<RoleResponse>>builder()
                .result(roleService.findAllRoles())
                .build();
    }

    @GetMapping("/{id}")
    public ApiResponse<RoleResponse> findById(@PathVariable("id") String id) {
        return ApiResponse.<RoleResponse>builder()
                .result(roleService.findById(id))
                .build();
    }

    @PostMapping("/{id}")
    public ApiResponse<RoleResponse> updateRole(
            @RequestBody @Valid UpdateRoleRequest updateRoleRequest,
            @PathVariable("id") String id) {
        return ApiResponse.<RoleResponse>builder()
                .result(roleService.updateRole(id, updateRoleRequest))
                .build();
    }

}


