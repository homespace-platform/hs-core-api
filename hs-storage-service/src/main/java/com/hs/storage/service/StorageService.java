package com.hs.storage.service;

import com.hs.common.dto.PageResponse;
import com.hs.storage.dto.request.CompleteUploadRequest;
import com.hs.storage.dto.request.CreateUploadRequest;
import com.hs.storage.dto.response.CreateUploadResponse;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;

public interface StorageService {
    CreateUploadResponse createUpload(CreateUploadRequest request);

    StorageObjectResponse completeUpload(String storageId, CompleteUploadRequest request);

    StorageObjectResponse getById(String storageId);

    /** Admin-only (ADMIN + STORAGE_VIEW): skips ownership, keeps existence/status/verified-access rules. */
    StorageObjectResponse adminGetById(String storageId);

    StorageUrlResponse adminCreateDownloadUrl(String storageId);

    /**
     * Internal, ACL-free reads for a caller that has ALREADY authorized the request (e.g. contract admin
     * path after CONTRACT_VIEW). Requires an authenticated caller; keeps existence/status/verified-access rules.
     * Never expose these directly from a controller.
     */
    StorageObjectResponse getByIdAuthorized(String storageId);

    StorageUrlResponse createViewUrlAuthorized(String storageId);

    StorageUrlResponse createDownloadUrlAuthorized(String storageId);

    StorageUrlResponse createViewUrl(String storageId);

    StorageUrlResponse createDownloadUrl(String storageId);

    String getOwnedPublicUrl(String storageId, StoragePurpose expectedPurpose);

    PageResponse<StorageObjectResponse> getCurrentUserObjects(
            String referenceType,
            String referenceId,
            StoragePurpose purpose,
            StorageStatus status,
            int page,
            int size);

    void delete(String storageId);

    StorageObjectResponse uploadDirect(
            byte[] data,
            String fileName,
            String contentType,
            StoragePurpose purpose,
            String referenceType,
            String referenceId,
            com.hs.storage.model.constant.StorageVisibility visibility);

    StorageObjectResponse uploadDirect(
            byte[] data,
            String fileName,
            String contentType,
            StoragePurpose purpose,
            String referenceType,
            String referenceId,
            com.hs.storage.model.constant.StorageVisibility visibility,
            String ownerId);

    byte[] downloadDirect(String storageId);

    /** Đọc nội bộ có giới hạn bytes thực tế, phục vụ kiểm tra PDF trước khi trả cho người dùng. */
    byte[] downloadDirect(String storageId, long maxBytes);

    StorageObjectResponse copyObject(
            String sourceStorageId,
            String newOwnerId,
            String newReferenceType,
            String newReferenceId,
            StoragePurpose expectedPurpose);

    void deleteS3ObjectDirect(String objectKey);
}
