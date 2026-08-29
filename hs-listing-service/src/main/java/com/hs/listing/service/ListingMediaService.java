package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.storage.config.StorageProperties;
import com.hs.listing.dto.request.CompleteListingMediaUploadRequest;
import com.hs.listing.dto.request.CreateListingMediaUploadRequest;
import com.hs.listing.dto.response.CompleteListingMediaUploadResponse;
import com.hs.listing.dto.response.CreateListingMediaUploadResponse;
import com.hs.listing.model.constant.ListingMediaType;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

@Service
public class ListingMediaService {

    private static final long MIB = 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4",
            "video/webm",
            "video/quicktime");

    private final StorageProperties properties;
    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    public ListingMediaService(StorageProperties properties, S3Client s3Client, S3Presigner s3Presigner) {
        this.properties = properties;
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    public CreateListingMediaUploadResponse createUpload(String ownerId, CreateListingMediaUploadRequest request) {
        if (ownerId == null || ownerId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);

        String normalizedContentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        validateFile(request.mediaType(), normalizedContentType, request.size());

        String extension = extractExtension(request.fileName());
        String objectKey = buildObjectKey(request.mediaType(), ownerId, UUID.randomUUID().toString(), extension);
        String publicUrl = buildPublicUrl(objectKey);

        PutObjectRequest putObject = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(objectKey)
                .contentType(normalizedContentType)
                .contentLength(request.size())
                .build();
        var presigned = s3Presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(properties.uploadUrlDuration())
                .putObjectRequest(putObject)
                .build());

        return new CreateListingMediaUploadResponse(
                presigned.url().toString(),
                "PUT",
                objectKey,
                publicUrl,
                Instant.now().plus(properties.uploadUrlDuration()));
    }

    public CompleteListingMediaUploadResponse completeUpload(
            String ownerId, CompleteListingMediaUploadRequest request) {
        if (ownerId == null || ownerId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);
        validateOwnedObjectKey(ownerId, request.objectKey());

        try {
            var head = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(request.objectKey())
                    .build());
            return new CompleteListingMediaUploadResponse(
                    request.objectKey(),
                    buildPublicUrl(request.objectKey()),
                    head.contentType(),
                    head.contentLength());
        } catch (NoSuchKeyException exception) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) throw new AppException(ErrorCode.INVALID_REQUEST);
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        } catch (SdkException exception) {
            throw new AppException(ErrorCode.UNCATEGORIZED_EXCEPTION);
        }
    }

    public boolean isAllowedMediaUrl(String ownerId, String url, ListingMediaType mediaType) {
        if (url == null || url.isBlank() || ownerId == null || ownerId.isBlank()) return false;
        return url.startsWith(buildOwnerPrefix(mediaType, ownerId));
    }

    public String buildPublicUrl(String objectKey) {
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(properties.bucket(), properties.region(), objectKey);
    }

    private void validateOwnedObjectKey(String ownerId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) throw new AppException(ErrorCode.INVALID_REQUEST);
        if (!objectKey.startsWith("listing_image/" + ownerId + "/")
                && !objectKey.startsWith("listing_video/" + ownerId + "/")) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
    }

    private String buildOwnerPrefix(ListingMediaType mediaType, String ownerId) {
        return buildPublicUrl(mediaPrefix(mediaType) + "/" + ownerId + "/");
    }

    private String buildObjectKey(ListingMediaType mediaType, String ownerId, String mediaId, String extension) {
        return mediaPrefix(mediaType) + "/" + ownerId + "/" + mediaId
                + (extension.isEmpty() ? "" : "." + extension);
    }

    private String mediaPrefix(ListingMediaType mediaType) {
        return mediaType == ListingMediaType.IMAGE ? "listing_image" : "listing_video";
    }

    private void validateFile(ListingMediaType mediaType, String contentType, long size) {
        boolean typeAllowed;
        long maxSize;
        if (mediaType == ListingMediaType.IMAGE) {
            typeAllowed = IMAGE_TYPES.contains(contentType);
            maxSize = 10 * MIB;
        } else {
            typeAllowed = VIDEO_TYPES.contains(contentType);
            maxSize = 100 * MIB;
        }
        if (!typeAllowed || size > maxSize) throw new AppException(ErrorCode.INVALID_REQUEST);
    }

    private String extractExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        String value = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9]{1,10}") ? value : "";
    }
}
