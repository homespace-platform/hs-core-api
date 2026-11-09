package com.hs.storage.dto.response;

import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import java.time.Instant;

public record StorageObjectResponse(
        String id,
        String originalName,
        String contentType,
        Long sizeBytes,
        String checksum,
        String extension,
        String ownerId,
        String referenceType,
        String referenceId,
        StoragePurpose purpose,
        StorageVisibility visibility,
        StorageStatus status,
        Instant createdAt,
        Instant updatedAt,
        String s3VersionId
) {
    public StorageObjectResponse(String id, String originalName, String contentType, Long sizeBytes,
            String checksum, String extension, String ownerId, String referenceType, String referenceId,
            StoragePurpose purpose, StorageVisibility visibility, StorageStatus status,
            Instant createdAt, Instant updatedAt) {
        this(id, originalName, contentType, sizeBytes, checksum, extension, ownerId, referenceType,
                referenceId, purpose, visibility, status, createdAt, updatedAt, null);
    }
}
