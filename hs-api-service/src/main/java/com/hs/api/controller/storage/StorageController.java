package com.hs.api.controller.storage;

import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.storage.dto.request.CompleteUploadRequest;
import com.hs.storage.dto.request.CreateUploadRequest;
import com.hs.storage.dto.response.CreateUploadResponse;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.service.StorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/storage")
@RequiredArgsConstructor
@Validated
public class StorageController {
    private final StorageService storageService;

    @PostMapping("/uploads")
    public ApiResponse<CreateUploadResponse> createUpload(@RequestBody @Valid CreateUploadRequest request) {
        return ApiResponse.<CreateUploadResponse>builder()
                .message("Upload URL created")
                .result(storageService.createUpload(request))
                .build();
    }

    @PostMapping("/{storageId}/complete")
    public ApiResponse<StorageObjectResponse> completeUpload(
            @PathVariable String storageId,
            @RequestBody(required = false) @Valid CompleteUploadRequest request) {
        CompleteUploadRequest body = request == null ? new CompleteUploadRequest(null) : request;
        return ApiResponse.<StorageObjectResponse>builder()
                .message("Upload completed")
                .result(storageService.completeUpload(storageId, body))
                .build();
    }

    @GetMapping("/{storageId}")
    public ApiResponse<StorageObjectResponse> getById(@PathVariable String storageId) {
        return ApiResponse.<StorageObjectResponse>builder()
                .result(storageService.getById(storageId))
                .build();
    }

    @GetMapping("/{storageId}/view-url")
    public ApiResponse<StorageUrlResponse> createViewUrl(@PathVariable String storageId) {
        return ApiResponse.<StorageUrlResponse>builder()
                .result(storageService.createViewUrl(storageId))
                .build();
    }

    @GetMapping("/{storageId}/download-url")
    public ApiResponse<StorageUrlResponse> createDownloadUrl(@PathVariable String storageId) {
        return ApiResponse.<StorageUrlResponse>builder()
                .result(storageService.createDownloadUrl(storageId))
                .build();
    }

    @GetMapping
    public PageResponse<StorageObjectResponse> getCurrentUserObjects(
            @RequestParam(required = false) String referenceType,
            @RequestParam(required = false) String referenceId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return storageService.getCurrentUserObjects(referenceType, referenceId, page, size);
    }

    @DeleteMapping("/{storageId}")
    public ApiResponse<Void> delete(@PathVariable String storageId) {
        storageService.delete(storageId);
        return ApiResponse.<Void>builder().message("Storage object deleted").build();
    }
}
