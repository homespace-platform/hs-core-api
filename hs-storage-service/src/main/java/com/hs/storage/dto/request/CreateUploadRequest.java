package com.hs.storage.dto.request;

import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateUploadRequest(
        @NotBlank @Size(max = 512) String fileName,
        @NotBlank @Size(max = 255) String contentType,
        @NotNull @Positive Long size,
        @NotNull StoragePurpose purpose,
        StorageVisibility visibility,
        @Size(max = 50) String referenceType,
        @Size(max = 255) String referenceId
) {
}
