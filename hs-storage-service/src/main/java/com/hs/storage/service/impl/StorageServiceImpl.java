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
import com.hs.storage.service.StorageService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

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

    private final StorageObjectRepository repository;
    private final StorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner presigner;

    @Override
    @Transactional
    public CreateUploadResponse createUpload(CreateUploadRequest request) {
        String ownerId = currentUserId();
        String normalizedContentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        String referenceType = normalizeReferenceType(request.referenceType());
        StoragePurpose effectivePurpose = resolvePurpose(
                request.purpose(), referenceType, normalizedContentType);
        validateFile(effectivePurpose, normalizedContentType, request.size());

        String storageId = UUID.randomUUID().toString();
        String extension = extractExtension(request.fileName());
        String objectKey = buildObjectKey(effectivePurpose, ownerId, storageId, extension);
        StorageVisibility visibility = request.visibility() == null
                ? StorageVisibility.PRIVATE
                : request.visibility();

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
            if (!object.getSizeBytes().equals(head.contentLength())
                    || !object.getContentType().equalsIgnoreCase(head.contentType())) {
                object.setStatus(StorageStatus.REJECTED);
                repository.save(object);
                throw new AppException(StorageErrorCode.STORAGE_UPLOAD_MISMATCH);
            }
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
    @Transactional(readOnly = true)
    public StorageUrlResponse createViewUrl(String storageId) {
        return createGetUrl(getAccessibleReadyObject(storageId), true);
    }

    @Override
    @Transactional(readOnly = true)
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
        object.setStatus(StorageStatus.DELETED);
        object.setActive(false);
        repository.save(object);
    }

    private StorageUrlResponse createGetUrl(StorageObject object, boolean inline) {
        String disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''"
                + URLEncoder.encode(object.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20");
        GetObjectRequest getObject = GetObjectRequest.builder()
                .bucket(object.getBucketName())
                .key(object.getObjectKey())
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
                || isAdmin()) {
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
                maxSize = 200 * MIB;
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

    private String buildObjectKey(StoragePurpose purpose, String ownerId, String id, String extension) {
        return purpose.name().toLowerCase(Locale.ROOT) + "/" + ownerId + "/" + id
                + (extension.isEmpty() ? "" : "." + extension);
    }

    private StoragePurpose resolvePurpose(
            StoragePurpose requestedPurpose, String referenceType, String contentType) {
        if (requestedPurpose == StoragePurpose.GENERAL && "LISTING".equals(referenceType)) {
            if (IMAGE_TYPES.contains(contentType)) return StoragePurpose.LISTING_IMAGE;
            if (VIDEO_TYPES.contains(contentType)) return StoragePurpose.LISTING_VIDEO;
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
                object.getCreatedAt(), object.getUpdatedAt());
    }
}
