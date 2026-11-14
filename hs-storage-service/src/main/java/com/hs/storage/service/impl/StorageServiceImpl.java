package com.hs.storage.service.impl;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.PageResponse;
import com.hs.common.advice.entity.AppException;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.storage.dto.request.CompleteUploadRequest;
import com.hs.storage.dto.request.CreateUploadRequest;
import com.hs.storage.dto.response.CreateUploadResponse;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.dto.response.StorageUrlResponse;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageAccessChecker;
import com.hs.storage.service.StorageService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {
    private static final long MIB = 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    // Only these purposes get the public/ key prefix (bucket policy exposes public/*); everything else is private/.
    private static final Set<StoragePurpose> PUBLIC_PURPOSES = EnumSet.of(
            StoragePurpose.USER_AVATAR, StoragePurpose.LISTING_IMAGE, StoragePurpose.LISTING_VIDEO,
            StoragePurpose.BRANCH_COVER_IMAGE, StoragePurpose.NEWS_IMAGE);

    private final StorageObjectRepository repository;
    private final StorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private List<StorageAccessChecker> accessCheckers = List.of();

    @Autowired(required = false)
    void setAccessCheckers(List<StorageAccessChecker> accessCheckers) {
        this.accessCheckers = accessCheckers != null ? List.copyOf(accessCheckers) : List.of();
    }

    @Override
    @Transactional
    public CreateUploadResponse createUpload(CreateUploadRequest request) {
        if(request.purpose()==StoragePurpose.PAYMENT_RECEIPT)throw new AppException(StorageErrorCode.STORAGE_ACCESS_DENIED);
        String ownerId = currentUserId();
        String normalizedContentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        String referenceType = normalizeReferenceType(request.referenceType());
        StoragePurpose effectivePurpose = resolvePurpose(
                request.purpose(), referenceType, normalizedContentType);
        validateFile(effectivePurpose, normalizedContentType, request.size());

        String storageId = UUID.randomUUID().toString();
        String extension = extractExtension(request.fileName());
        String objectKey = buildObjectKey(effectivePurpose, ownerId, storageId, extension);
        StorageVisibility visibility = resolveVisibility(effectivePurpose, request.visibility());

        StorageObject object = StorageObject.builder()
                .id(storageId)
                .originalName(request.fileName().trim())
                .objectKey(objectKey)
                .bucketName(properties.bucket())
                .contentType(normalizedContentType)
                .sizeBytes(request.size())
                .extension(extension)
                .ownerId(ownerId)
                .referenceType(referenceType)
                .referenceId(normalizeNullable(request.referenceId()))
                .purpose(effectivePurpose)
                .visibility(visibility)
                .status(StorageStatus.PENDING)
                .build();
        repository.save(object);

        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(normalizedContentType)
                .contentLength(request.size())
                .build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.uploadUrlDuration())
                .putObjectRequest(putObject)
                .build());

        return new CreateUploadResponse(
                storageId,
                presigned.url().toString(),
                "PUT",
                objectKey,
                Instant.now().plus(properties.uploadUrlDuration()));
    }

    @Override
    @Transactional
    public StorageObjectResponse completeUpload(String storageId, CompleteUploadRequest request) {
        StorageObject object = getOwnedObject(storageId);
        if (object.getStatus() == StorageStatus.READY) {
            return toResponse(object);
        }
        if (object.getStatus() != StorageStatus.PENDING) {
            throw new AppException(StorageErrorCode.STORAGE_UPLOAD_MISMATCH);
        }

        try {
            var head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(object.getBucketName())
                    .key(object.getObjectKey())
                    .build());
            requireCreatedVersion(head.versionId());
            if (!object.getSizeBytes().equals(head.contentLength())
                    || !object.getContentType().equalsIgnoreCase(head.contentType())) {
                object.setStatus(StorageStatus.REJECTED);
                repository.save(object);
                throw new AppException(StorageErrorCode.STORAGE_UPLOAD_MISMATCH);
            }

            // Inspect magic bytes to verify real MIME and block executables/scripts
            if (object.getSizeBytes() > 0) {
                try (var is = s3Client.getObject(GetObjectRequest.builder()
                        .bucket(object.getBucketName())
                        .key(object.getObjectKey())
                        .versionId(head.versionId())
                        .range("bytes=0-63")
                        .build())) {
                    byte[] headerBytes = is.readNBytes(64);
                    validateMagicBytes(object.getPurpose(), object.getContentType(), headerBytes);
                } catch (AppException e) {
                    object.setStatus(StorageStatus.REJECTED);
                    repository.save(object);
                    throw e;
                } catch (Exception e) {
                    throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
                }
            }

            object.setS3VersionId(head.versionId());
            object.setChecksum(normalizeNullable(request.checksum()));
            object.setStatus(StorageStatus.READY);
            return toResponse(repository.save(object));
        } catch (NoSuchKeyException exception) {
            throw new AppException(StorageErrorCode.STORAGE_UPLOAD_NOT_FOUND);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new AppException(StorageErrorCode.STORAGE_UPLOAD_NOT_FOUND);
            }
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        } catch (AppException exception) {
            throw exception;
        } catch (SdkException exception) {
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public StorageObjectResponse getById(String storageId) {
        return toResponse(getAccessibleObject(storageId));
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    public StorageUrlResponse createViewUrl(String storageId) {
        return createGetUrl(getAccessibleReadyObject(storageId), true);
    }

    @Override
    @Transactional(readOnly = true, noRollbackFor = AppException.class)
    public StorageUrlResponse createDownloadUrl(String storageId) {
        return createGetUrl(getAccessibleReadyObject(storageId), false);
    }

    @Override
    @Transactional(readOnly = true)
    public String getOwnedPublicUrl(String storageId, StoragePurpose expectedPurpose) {
        StorageObject object = getOwnedObject(storageId);
        if (object.getStatus() != StorageStatus.READY) {
            throw new AppException(StorageErrorCode.STORAGE_NOT_READY);
        }
        if (object.getPurpose() != expectedPurpose) {
            throw new AppException(StorageErrorCode.STORAGE_INVALID_PURPOSE);
        }
        if (object.getVisibility() != StorageVisibility.PUBLIC) {
            throw new AppException(StorageErrorCode.STORAGE_OBJECT_NOT_PUBLIC);
        }
        requireDirectAccessAllowed(object);
        return buildS3PublicUrl(object);
    }

    private String buildS3PublicUrl(StorageObject object) {
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(object.getBucketName(), properties.region(), object.getObjectKey());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StorageObjectResponse> getCurrentUserObjects(
            String referenceType,
            String referenceId,
            StoragePurpose purpose,
            StorageStatus status,
            int page,
            int size) {
        String ownerId = currentUserId();
        var pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Specification<StorageObject> specification = (root, query, cb) -> cb.and(
                cb.equal(root.get("ownerId"), ownerId),
                cb.isTrue(root.get("active")));
        if (referenceType != null && !referenceType.isBlank()
                && referenceId != null && !referenceId.isBlank()) {
            String normalizedReferenceType = referenceType.trim().toUpperCase(Locale.ROOT);
            String normalizedReferenceId = referenceId.trim();
            specification = specification.and((root, query, cb) -> cb.and(
                    cb.equal(root.get("referenceType"), normalizedReferenceType),
                    cb.equal(root.get("referenceId"), normalizedReferenceId)));
        }
        if (purpose != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("purpose"), purpose));
        }
        if (status != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return new PageResponse<>(repository.findAll(specification, pageable).map(this::toResponse));
    }

    @Override
    @Transactional
    public void delete(String storageId) {
        StorageObject object = getOwnedObject(storageId);
        if (accessCheckers.stream().anyMatch(checker -> checker.preventsDeletion(object))) {
            throw new AppException(StorageErrorCode.STORAGE_ACCESS_DENIED);
        }
        object.setStatus(StorageStatus.DELETED);
        object.setActive(false);
        repository.save(object);
    }

    @Override
    @Transactional
    public StorageObjectResponse uploadDirect(
            byte[] data,
            String fileName,
            String contentType,
            StoragePurpose purpose,
            String referenceType,
            String referenceId,
            StorageVisibility visibility) {
        return uploadDirect(data, fileName, contentType, purpose, referenceType, referenceId, visibility, null);
    }

    @Override
    @Transactional
    public StorageObjectResponse uploadDirect(
            byte[] data,
            String fileName,
            String contentType,
            StoragePurpose purpose,
            String referenceType,
            String referenceId,
            StorageVisibility visibility,
            String ownerId) {
        String normalizedContentType = contentType.trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(normalizedContentType) || "image/pjpeg".equals(normalizedContentType)) {
            normalizedContentType = "image/jpeg";
        }
        validateFile(purpose, normalizedContentType, (long) data.length);
        validateMagicBytes(purpose, normalizedContentType, data);

        String effectiveOwnerId = ownerId;
        if (effectiveOwnerId == null || effectiveOwnerId.isBlank()) {
            try {
                effectiveOwnerId = currentUserId();
            } catch (Exception e) {
                effectiveOwnerId = "system";
            }
        }

        String storageId = UUID.randomUUID().toString();
        String extension = extractExtension(fileName);
        String objectKey = buildObjectKey(purpose, effectiveOwnerId, storageId, extension);

        String uploadedVersionId;
        try {
            PutObjectRequest putObject = PutObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength((long) data.length)
                    .build();

            var uploaded = s3Client.putObject(putObject, software.amazon.awssdk.core.sync.RequestBody.fromBytes(data));
            uploadedVersionId = uploaded == null ? null : uploaded.versionId();
            requireCreatedVersion(uploadedVersionId);
        } catch (AppException failure) {
            throw failure;
        } catch (Exception e) {
            log.error("Failed to putObject to S3 bucket [{}] key [{}]: ", properties.bucket(), objectKey, e);
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        }

        StorageObject object = StorageObject.builder()
                .id(storageId)
                .originalName(fileName.trim())
                .objectKey(objectKey)
                .bucketName(properties.bucket())
                .s3VersionId(uploadedVersionId)
                .contentType(contentType)
                .sizeBytes((long) data.length)
                .extension(extension)
                .ownerId(effectiveOwnerId)
                .referenceType(normalizeReferenceType(referenceType))
                .referenceId(normalizeNullable(referenceId))
                .purpose(purpose)
                .visibility(resolveVisibility(purpose, visibility))
                .status(StorageStatus.READY)
                .build();

        StorageObject saved;
        try {
            saved = repository.save(object);
        } catch (Exception e) {
            log.error("Failed to save storage object metadata for id={}, cleaning up S3 object key={}", storageId, objectKey, e);
            cleanupFailedWrite(objectKey, uploadedVersionId);
            throw e;
        }

        return toResponse(saved);
    }

    @Override
    public byte[] downloadDirect(String storageId) {
        StorageObject object = findActive(storageId);
        if (object.getStatus() != StorageStatus.READY) {
            throw new AppException(StorageErrorCode.STORAGE_NOT_READY);
        }
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(object.getBucketName())
                    .key(object.getObjectKey())
                    .versionId(object.getS3VersionId())
                    .build();
            return s3Client.getObjectAsBytes(getObjectRequest).asByteArray();
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) throw new AppException(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND);
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        } catch (Exception e) {
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        }
    }

    @Override
    public byte[] downloadDirect(String storageId, long maxBytes) {
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("A bounded byte-array download limit is required");
        }
        StorageObject object = findActive(storageId);
        if (object.getStatus() != StorageStatus.READY) {
            throw new AppException(StorageErrorCode.STORAGE_NOT_READY);
        }
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(object.getBucketName()).key(object.getObjectKey())
                .versionId(object.getS3VersionId()).build();
        try (var input = s3Client.getObject(request)) {
            Long declaredLength = input.response().contentLength();
            if (declaredLength != null && declaredLength > maxBytes) {
                throw new AppException(StorageErrorCode.STORAGE_FILE_TOO_LARGE);
            }
            var output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, maxBytes - total + 1))) != -1) {
                total += count;
                if (total > maxBytes) throw new AppException(StorageErrorCode.STORAGE_FILE_TOO_LARGE);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (AppException failure) {
            throw failure;
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) throw new AppException(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND);
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        } catch (Exception failure) {
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        }
    }

    @Override
    @Transactional
    public StorageObjectResponse copyObject(
            String sourceStorageId,
            String newOwnerId,
            String newReferenceType,
            String newReferenceId,
            StoragePurpose expectedPurpose) {
        StorageObject source = findActive(sourceStorageId);
        if (source.getStatus() != StorageStatus.READY) {
            throw new AppException(StorageErrorCode.STORAGE_NOT_READY);
        }
        if (expectedPurpose != null && source.getPurpose() != expectedPurpose) {
            throw new AppException(StorageErrorCode.STORAGE_INVALID_PURPOSE);
        }

        String targetOwnerId = (newOwnerId != null && !newOwnerId.isBlank()) ? newOwnerId : currentUserId();
        String newStorageId = UUID.randomUUID().toString();
        String extension = source.getExtension() != null ? source.getExtension() : "";
        StoragePurpose targetPurpose = expectedPurpose != null ? expectedPurpose : source.getPurpose();
        String newObjectKey = buildObjectKey(targetPurpose, targetOwnerId, newStorageId, extension);

        String copiedVersionId;
        try {
            var copyReq = CopyObjectRequest.builder()
                    .sourceBucket(source.getBucketName())
                    .sourceKey(source.getObjectKey())
                    .sourceVersionId(source.getS3VersionId())
                    .destinationBucket(properties.bucket())
                    .destinationKey(newObjectKey)
                    .contentType(source.getContentType())
                    .build();
            var copiedResult = s3Client.copyObject(copyReq);
            copiedVersionId = copiedResult == null ? null : copiedResult.versionId();
            requireCreatedVersion(copiedVersionId);
        } catch (AppException failure) {
            throw failure;
        } catch (Exception e) {
            log.error("Failed to copy S3 object from [{}/{}] to [{}/{}]: ",
                    source.getBucketName(), source.getObjectKey(), properties.bucket(), newObjectKey, e);
            throw new AppException(StorageErrorCode.STORAGE_PROVIDER_ERROR);
        }

        StorageObject copied = StorageObject.builder()
                .id(newStorageId)
                .originalName(source.getOriginalName())
                .objectKey(newObjectKey)
                .bucketName(properties.bucket())
                .s3VersionId(copiedVersionId)
                .contentType(source.getContentType())
                .sizeBytes(source.getSizeBytes())
                .checksum(source.getChecksum())
                .extension(extension)
                .ownerId(targetOwnerId)
                .referenceType(normalizeReferenceType(newReferenceType))
                .referenceId(normalizeNullable(newReferenceId))
                .purpose(targetPurpose)
                .visibility(resolveVisibility(targetPurpose, source.getVisibility()))
                .status(StorageStatus.READY)
                .build();
        copied.setActive(true);

        try {
            StorageObject saved = repository.save(copied);
            return toResponse(saved);
        } catch (Exception e) {
            log.error("Failed to save copied storage object metadata for id={}, cleaning up S3 object key={}",
                    newStorageId, newObjectKey, e);
            cleanupFailedWrite(newObjectKey, copiedVersionId);
            throw e;
        }
    }

    @Override
    public void deleteS3ObjectDirect(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(objectKey)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to delete S3 object key [{}]: {}", objectKey, ex.getMessage());
        }
    }

    private void cleanupFailedWrite(String objectKey, String versionId) {
        if (versionId != null) {
            // shortcut: IAM runtime không xóa version; quản trị viên dọn orphan đúng version theo log.
            log.warn("Retained orphan S3 version after metadata failure: bucket={}, key={}, versionId={}",
                    properties.bucket(), objectKey, versionId);
            return;
        }
        deleteS3ObjectDirect(objectKey);
    }

    private void requireCreatedVersion(String versionId) {
        if (versionId == null || versionId.isBlank() || "null".equals(versionId)) {
            throw new AppException(StorageErrorCode.STORAGE_VERSIONING_REQUIRED);
        }
    }

    private StorageUrlResponse createGetUrl(StorageObject object, boolean inline) {
        requireDirectAccessAllowed(object);
        String disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(object.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20");
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(object.getBucketName())
                .key(object.getObjectKey())
                .versionId(object.getS3VersionId())
                .responseContentType(object.getContentType())
                .responseContentDisposition(disposition)
                .build();
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(properties.downloadUrlDuration())
                .getObjectRequest(getObject)
                .build());
        return new StorageUrlResponse(
                presigned.url().toString(), Instant.now().plus(properties.downloadUrlDuration()));
    }

    private void requireDirectAccessAllowed(StorageObject object) {
        if (accessCheckers.stream().anyMatch(checker -> checker.requiresVerifiedAccess(object))) {
            throw new AppException(StorageErrorCode.STORAGE_ACCESS_DENIED);
        }
    }

    private StorageObject getAccessibleReadyObject(String storageId) {
        StorageObject object = getAccessibleObject(storageId);
        if (object.getStatus() != StorageStatus.READY) {
            throw new AppException(StorageErrorCode.STORAGE_NOT_READY);
        }
        return object;
    }

    private StorageObject getAccessibleObject(String storageId) {
        StorageObject object = findActive(storageId);
        if (object.getVisibility() == StorageVisibility.PUBLIC) {
            return object;
        }
        String userId = currentUserId();
        if (object.getVisibility() == StorageVisibility.AUTHENTICATED
                || object.getOwnerId().equals(userId)
                || isAdmin()
                || accessCheckers.stream().anyMatch(checker -> checker.canAccess(userId, object))) {
            return object;
        }
        throw new AppException(StorageErrorCode.STORAGE_ACCESS_DENIED);
    }

    private StorageObject getOwnedObject(String storageId) {
        StorageObject object = findActive(storageId);
        if (!object.getOwnerId().equals(currentUserId()) && !isAdmin()) {
            throw new AppException(StorageErrorCode.STORAGE_ACCESS_DENIED);
        }
        return object;
    }

    private StorageObject findActive(String storageId) {
        StorageObject object = repository.findById(storageId)
                .orElseThrow(() -> new AppException(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND));
        if (!Boolean.TRUE.equals(object.getActive())) {
            throw new AppException(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND);
        }
        return object;
    }

    private String currentUserId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(StorageErrorCode.STORAGE_UNAUTHENTICATED);
        }
        return context.userId();
    }

    private boolean isAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ADMIN")
                        || authority.getAuthority().equals("ROLE_ADMIN"));
    }

    private void validateFile(StoragePurpose purpose, String contentType, long size) {
        boolean typeAllowed;
        long maxSize;
        switch (purpose) {
            case USER_AVATAR -> { typeAllowed = IMAGE_TYPES.contains(contentType); maxSize = 5 * MIB; }
            case CONTRACT_DOCUMENT -> { typeAllowed = DOCUMENT_TYPES.contains(contentType); maxSize = 25 * MIB; }
            case SIGNATURE_PREPARED_DOCUMENT, PAYMENT_RECEIPT -> { typeAllowed = contentType.equals("application/pdf"); maxSize = 25 * MIB; }
            case IDENTITY_DOCUMENT -> {
                typeAllowed = IMAGE_TYPES.contains(contentType) || contentType.equals("application/pdf");
                maxSize = 15 * MIB;
            }
            case CHAT_ATTACHMENT -> {
                typeAllowed = IMAGE_TYPES.contains(contentType) || DOCUMENT_TYPES.contains(contentType);
                maxSize = 25 * MIB;
            }
            case LISTING_IMAGE -> {
                typeAllowed = IMAGE_TYPES.contains(contentType);
                maxSize = 25 * MIB;
            }
            case LISTING_VIDEO -> {
                typeAllowed = VIDEO_TYPES.contains(contentType);
                maxSize = 500 * MIB;
            }
            case NEWS_IMAGE -> {
                typeAllowed = IMAGE_TYPES.contains(contentType);
                maxSize = 25 * MIB;
            }
            case PAYMENT_PROOF -> {
                typeAllowed = IMAGE_TYPES.contains(contentType) || contentType.equals("application/pdf");
                maxSize = 15 * MIB;
            }
            case BRANCH_COVER_IMAGE -> {
                typeAllowed = IMAGE_TYPES.contains(contentType);
                maxSize = 15 * MIB;
            }
            case GENERAL -> {
                typeAllowed = IMAGE_TYPES.contains(contentType) || DOCUMENT_TYPES.contains(contentType);
                maxSize = 25 * MIB;
            }
            default -> throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }
        if (!typeAllowed) throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        if (size > maxSize) throw new AppException(StorageErrorCode.STORAGE_FILE_TOO_LARGE);
    }

    private void validateMagicBytes(StoragePurpose purpose, String contentType, byte[] data) {
        if (data == null || data.length < 4) {
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }

        // Check for blocked executable signatures
        // DOS/PE executable (MZ)
        if (data[0] == 0x4D && data[1] == 0x5A) {
            log.warn("Blocked executable file (MZ magic bytes) for purpose {}", purpose);
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }
        // ELF executable
        if (data.length >= 4 && data[0] == 0x7F && data[1] == 0x45 && data[2] == 0x4C && data[3] == 0x46) {
            log.warn("Blocked executable file (ELF magic bytes) for purpose {}", purpose);
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }

        // Check for scripts, HTML, SVG, XML
        int checkLen = Math.min(data.length, 64);
        String headerAscii = new String(data, 0, checkLen, StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT).trim();
        if (headerAscii.startsWith("<?xml") || headerAscii.startsWith("<svg")
                || headerAscii.startsWith("<html") || headerAscii.startsWith("<!doctype")
                || headerAscii.startsWith("<?php") || headerAscii.startsWith("#!/")) {
            log.warn("Blocked script or markup file with header [{}] for purpose {}", headerAscii, purpose);
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }

        boolean isJpeg = data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF;
        boolean isPng = data.length >= 8 && (data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47
                && data[4] == 0x0D && data[5] == 0x0A && data[6] == 0x1A && data[7] == 0x0A;
        boolean isWebp = data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
        boolean isPdf = data.length >= 4 && data[0] == '%' && data[1] == 'P' && data[2] == 'D' && data[3] == 'F';

        if ((purpose == StoragePurpose.SIGNATURE_PREPARED_DOCUMENT || purpose == StoragePurpose.PAYMENT_RECEIPT) && !isPdf) {
            log.warn("Invalid magic bytes for prepared signature PDF");
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }

        if (purpose == StoragePurpose.PAYMENT_PROOF) {
            if (!isJpeg && !isPng && !isWebp && !isPdf) {
                log.warn("Invalid magic bytes for PAYMENT_PROOF with declared contentType {}", contentType);
                throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
            }
        }

        if (purpose == StoragePurpose.BRANCH_COVER_IMAGE) {
            if (!isJpeg && !isPng && !isWebp) {
                log.warn("Invalid magic bytes for BRANCH_COVER_IMAGE with declared contentType {}", contentType);
                throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
            }
        }
    }

    /** Visibility is decided by purpose, never trusted from the caller; chat keeps its existing AUTHENTICATED mode. */
    private StorageVisibility resolveVisibility(StoragePurpose purpose, StorageVisibility requested) {
        if (PUBLIC_PURPOSES.contains(purpose)) return StorageVisibility.PUBLIC;
        if (purpose == StoragePurpose.CHAT_ATTACHMENT && requested == StorageVisibility.AUTHENTICATED) {
            return StorageVisibility.AUTHENTICATED;
        }
        return StorageVisibility.PRIVATE;
    }

    private String buildObjectKey(StoragePurpose purpose, String ownerId, String id, String extension) {
        String scope = PUBLIC_PURPOSES.contains(purpose) ? "public" : "private";
        return scope + "/" + purpose.name().toLowerCase(Locale.ROOT) + "/" + ownerId + "/" + id
                + (extension.isEmpty() ? "" : "." + extension);
    }

    private StoragePurpose resolvePurpose(
            StoragePurpose requestedPurpose, String referenceType, String contentType) {
        if (requestedPurpose == StoragePurpose.GENERAL && "LISTING".equals(referenceType)) {
            if (IMAGE_TYPES.contains(contentType)) return StoragePurpose.LISTING_IMAGE;
            if (VIDEO_TYPES.contains(contentType)) return StoragePurpose.LISTING_VIDEO;
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }
        if (requestedPurpose == StoragePurpose.GENERAL && ("BRANCH".equals(referenceType) || "PROPERTY_BRANCH".equals(referenceType))) {
            if (IMAGE_TYPES.contains(contentType)) return StoragePurpose.BRANCH_COVER_IMAGE;
            throw new AppException(StorageErrorCode.STORAGE_INVALID_FILE_TYPE);
        }
        return requestedPurpose;
    }

    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String value = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9]{1,10}") ? value : "";
    }

    private String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String normalizeReferenceType(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private StorageObjectResponse toResponse(StorageObject object) {
        return new StorageObjectResponse(
                object.getId(), object.getOriginalName(), object.getContentType(), object.getSizeBytes(),
                object.getChecksum(), object.getExtension(), object.getOwnerId(), object.getReferenceType(),
                object.getReferenceId(), object.getPurpose(), object.getVisibility(), object.getStatus(),
                object.getCreatedAt(), object.getUpdatedAt(), object.getS3VersionId());
    }
}
