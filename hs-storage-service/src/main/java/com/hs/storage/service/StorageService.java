package com.hs.storage.service;

import com.hs.common.dto.PageResponse;
import com.hs.storage.dto.request.CompleteUploadRequest;
import com.hs.storage.dto.request.CreateUploadRequest;
import com.hs.storage.dto.response.CreateUploadResponse;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.model.constant.StoragePurpose;

public interface StorageService {
    CreateUploadResponse createUpload(CreateUploadRequest request);

    StorageObjectResponse completeUpload(String storageId, CompleteUploadRequest request);

    StorageObjectResponse getById(String storageId);

    StorageUrlResponse createViewUrl(String storageId);

    StorageUrlResponse createDownloadUrl(String storageId);

    String getOwnedPublicUrl(String storageId, StoragePurpose expectedPurpose);

    PageResponse<StorageObjectResponse> getCurrentUserObjects(
            String referenceType, String referenceId, int page, int size);

    void delete(String storageId);
}
