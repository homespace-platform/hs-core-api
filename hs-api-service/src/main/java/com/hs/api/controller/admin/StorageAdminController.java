package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/admin/storage")
@PreAuthorize("hasAuthority('ADMIN') and hasAuthority('STORAGE_VIEW')")
public class StorageAdminController {
    private final StorageService storage;

    @GetMapping("/{id}")
    public ApiResponse<StorageObjectResponse> get(@PathVariable String id) {
        return ApiResponse.<StorageObjectResponse>builder().result(storage.adminGetById(id)).build();
    }

    @GetMapping("/{id}/download-url")
    public ApiResponse<StorageUrlResponse> downloadUrl(@PathVariable String id) {
        return ApiResponse.<StorageUrlResponse>builder().result(storage.adminCreateDownloadUrl(id)).build();
    }
}
