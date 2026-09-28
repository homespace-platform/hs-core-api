package com.hs.storage.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.dto.request.CreateUploadRequest;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.hs.storage.model.constant.StorageStatus;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import java.util.Optional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageServiceImplTest {

    @Mock
    private StorageObjectRepository repository;

    @Mock
    private StorageProperties properties;

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner presigner;

    @InjectMocks
    private StorageServiceImpl storageService;

    @BeforeEach
    void setUp() {
        UserContextHolder.set(new UserContext("test-user-id", "test@example.com"));
        lenient().when(properties.bucket()).thenReturn("test-bucket");
        lenient().when(properties.region()).thenReturn("ap-southeast-1");
        lenient().when(properties.uploadUrlDuration()).thenReturn(Duration.ofMinutes(15));
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void uploadDirect_validJpegProof_success() {
        byte[] validJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = storageService.uploadDirect(
                validJpeg,
                "receipt.jpg",
                "image/jpeg",
                StoragePurpose.PAYMENT_PROOF,
                "PAYMENT_REQUEST",
                "pr-123",
                StorageVisibility.PRIVATE
        );

        assertNotNull(response);
        assertEquals("image/jpeg", response.contentType());
        assertEquals("PAYMENT_PROOF", response.purpose().name());
    }

    @Test
    void uploadDirect_validPngProof_success() {
        byte[] validPng = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = storageService.uploadDirect(
                validPng,
                "receipt.png",
                "image/png",
                StoragePurpose.PAYMENT_PROOF,
                "PAYMENT_REQUEST",
                "pr-123",
                StorageVisibility.PRIVATE
        );

        assertNotNull(response);
        assertEquals("image/png", response.contentType());
    }

    @Test
    void uploadDirect_validPdfProof_success() {
        byte[] validPdf = "%PDF-1.4 test content".getBytes(StandardCharsets.US_ASCII);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = storageService.uploadDirect(
                validPdf,
                "invoice.pdf",
                "application/pdf",
                StoragePurpose.PAYMENT_PROOF,
                "PAYMENT_REQUEST",
                "pr-123",
                StorageVisibility.PRIVATE
        );

        assertNotNull(response);
        assertEquals("application/pdf", response.contentType());
    }

    @Test
    void uploadDirect_preparedSignaturePdf_success() {
        byte[] validPdf = "%PDF-1.4 test content".getBytes(StandardCharsets.US_ASCII);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = storageService.uploadDirect(
                validPdf,
                "contract.pdf",
                "application/pdf",
                StoragePurpose.SIGNATURE_PREPARED_DOCUMENT,
                "CONTRACT",
                "contract-123",
                StorageVisibility.PRIVATE
        );

        assertNotNull(response);
        assertEquals("application/pdf", response.contentType());
        assertEquals(StoragePurpose.SIGNATURE_PREPARED_DOCUMENT, response.purpose());
    }

    @Test
    void uploadDirect_preparedSignatureDocumentRejectsDocx() {
        byte[] docxBytes = new byte[]{0x50, 0x4B, 0x03, 0x04};

        AppException ex = assertThrows(AppException.class, () -> storageService.uploadDirect(
                docxBytes,
                "contract.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                StoragePurpose.SIGNATURE_PREPARED_DOCUMENT,
                "CONTRACT",
                "contract-123",
                StorageVisibility.PRIVATE
        ));

        assertEquals(StorageErrorCode.STORAGE_INVALID_FILE_TYPE.getCode(), ex.getCode());
    }

    @Test
    void uploadDirect_preparedSignaturePdfRejectsNonPdfBytes() {
        byte[] nonPdfBytes = "not a PDF".getBytes(StandardCharsets.US_ASCII);

        AppException ex = assertThrows(AppException.class, () -> storageService.uploadDirect(
                nonPdfBytes,
                "contract.pdf",
                "application/pdf",
                StoragePurpose.SIGNATURE_PREPARED_DOCUMENT,
                "CONTRACT",
                "contract-123",
                StorageVisibility.PRIVATE
        ));

        assertEquals(StorageErrorCode.STORAGE_INVALID_FILE_TYPE.getCode(), ex.getCode());
    }

    @Test
    void uploadDirect_blockedExecutableMz_throwsException() {
        byte[] fakeExe = new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x00}; // MZ header

        AppException ex = assertThrows(AppException.class, () ->
                storageService.uploadDirect(
                        fakeExe,
                        "malicious.png",
                        "image/png",
                        StoragePurpose.PAYMENT_PROOF,
                        "PAYMENT_REQUEST",
                        "pr-123",
                        StorageVisibility.PRIVATE
                )
        );
        assertEquals(StorageErrorCode.STORAGE_INVALID_FILE_TYPE.getCode(), ex.getCode());
    }

    @Test
    void uploadDirect_blockedExecutableElf_throwsException() {
        byte[] fakeElf = new byte[]{0x7F, 0x45, 0x4C, 0x46, 0x01}; // ELF header

        AppException ex = assertThrows(AppException.class, () ->
                storageService.uploadDirect(
                        fakeElf,
                        "binary.pdf",
                        "application/pdf",
                        StoragePurpose.PAYMENT_PROOF,
                        "PAYMENT_REQUEST",
                        "pr-123",
                        StorageVisibility.PRIVATE
                )
        );
        assertEquals(StorageErrorCode.STORAGE_INVALID_FILE_TYPE.getCode(), ex.getCode());
    }

    @Test
    void uploadDirect_blockedSvgOrXml_throwsException() {
        byte[] svgPayload = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);

        AppException ex = assertThrows(AppException.class, () ->
                storageService.uploadDirect(
                        svgPayload,
                        "fake.png",
                        "image/png",
                        StoragePurpose.PAYMENT_PROOF,
                        "PAYMENT_REQUEST",
                        "pr-123",
                        StorageVisibility.PRIVATE
                )
        );
        assertEquals(StorageErrorCode.STORAGE_INVALID_FILE_TYPE.getCode(), ex.getCode());
    }

    @Test
    void uploadDirect_blockedHtmlPayload_throwsException() {
        byte[] htmlPayload = "<html><body>phishing</body></html>".getBytes(StandardCharsets.UTF_8);

        AppException ex = assertThrows(AppException.class, () ->
                storageService.uploadDirect(
                        htmlPayload,
                        "fake.jpg",
                        "image/jpeg",
                        StoragePurpose.PAYMENT_PROOF,
                        "PAYMENT_REQUEST",
                        "pr-123",
                        StorageVisibility.PRIVATE
                )
        );
        assertEquals(StorageErrorCode.STORAGE_INVALID_FILE_TYPE.getCode(), ex.getCode());
    }

    @Test
    void uploadDirect_fileTooLarge_throwsException() {
        byte[] largeData = new byte[16 * 1024 * 1024]; // 16MB > 15MB limit for PAYMENT_PROOF

        AppException ex = assertThrows(AppException.class, () ->
                storageService.uploadDirect(
                        largeData,
                        "huge.jpg",
                        "image/jpeg",
                        StoragePurpose.PAYMENT_PROOF,
                        "PAYMENT_REQUEST",
                        "pr-123",
                        StorageVisibility.PRIVATE
                )
        );
        assertEquals(StorageErrorCode.STORAGE_FILE_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    void createUpload_listingVideoAllows500MiB() throws Exception {
        var presigned = mock(PresignedPutObjectRequest.class);
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
        when(presigned.url()).thenReturn(URI.create("https://example.com/upload").toURL());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = storageService.createUpload(new CreateUploadRequest(
                "listing.mp4",
                "video/mp4",
                500L * 1024 * 1024,
                StoragePurpose.GENERAL,
                StorageVisibility.PUBLIC,
                "LISTING",
                "listing"
        ));

        assertNotNull(response);
        var objectCaptor = ArgumentCaptor.forClass(StorageObject.class);
        verify(repository).save(objectCaptor.capture());
        assertEquals(StoragePurpose.LISTING_VIDEO, objectCaptor.getValue().getPurpose());
    }

    @Test
    void createUpload_listingVideoOver500MiB_throwsException() {
        AppException ex = assertThrows(AppException.class, () ->
                storageService.createUpload(new CreateUploadRequest(
                        "listing.mp4",
                        "video/mp4",
                        500L * 1024 * 1024 + 1,
                        StoragePurpose.GENERAL,
                        StorageVisibility.PUBLIC,
                        "LISTING",
                        "listing"
                ))
        );

        assertEquals(StorageErrorCode.STORAGE_FILE_TOO_LARGE.getCode(), ex.getCode());
    }

    @Test
    void copyObject_success() {
        StorageObject source = StorageObject.builder()
                .id("src-123")
                .originalName("photo.jpg")
                .objectKey("listing_image/owner-1/src-123.jpg")
                .bucketName("test-bucket")
                .contentType("image/jpeg")
                .sizeBytes(1024L)
                .extension("jpg")
                .ownerId("owner-1")
                .purpose(StoragePurpose.LISTING_IMAGE)
                .visibility(StorageVisibility.PUBLIC)
                .status(StorageStatus.READY)
                .build();
        source.setActive(true);

        when(repository.findById("src-123")).thenReturn(Optional.of(source));
        when(repository.save(any(StorageObject.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = storageService.copyObject(
                "src-123",
                "owner-2",
                "LISTING",
                "listing-456",
                StoragePurpose.LISTING_IMAGE);

        assertNotNull(response);
        assertNotEquals("src-123", response.id());
        assertEquals("owner-2", response.ownerId());
        assertEquals("LISTING", response.referenceType());
        assertEquals("listing-456", response.referenceId());
        assertEquals(StorageStatus.READY, response.status());

        ArgumentCaptor<CopyObjectRequest> copyCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(copyCaptor.capture());
        assertEquals("test-bucket", copyCaptor.getValue().sourceBucket());
        assertEquals("listing_image/owner-1/src-123.jpg", copyCaptor.getValue().sourceKey());
        assertEquals("test-bucket", copyCaptor.getValue().destinationBucket());
        assertTrue(copyCaptor.getValue().destinationKey().startsWith("listing_image/owner-2/"));
        assertTrue(copyCaptor.getValue().destinationKey().endsWith(".jpg"));
    }

    @Test
    void copyObject_sourceNotReady_throwsException() {
        StorageObject source = StorageObject.builder()
                .id("src-pending")
                .status(StorageStatus.PENDING)
                .build();
        source.setActive(true);
        when(repository.findById("src-pending")).thenReturn(Optional.of(source));

        AppException ex = assertThrows(AppException.class, () ->
                storageService.copyObject("src-pending", "owner-1", "LISTING", "l-1", StoragePurpose.LISTING_IMAGE));
        assertEquals(StorageErrorCode.STORAGE_NOT_READY.getCode(), ex.getCode());
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void copyObject_purposeMismatch_throwsException() {
        StorageObject source = StorageObject.builder()
                .id("src-video")
                .status(StorageStatus.READY)
                .purpose(StoragePurpose.LISTING_VIDEO)
                .build();
        source.setActive(true);
        when(repository.findById("src-video")).thenReturn(Optional.of(source));

        AppException ex = assertThrows(AppException.class, () ->
                storageService.copyObject("src-video", "owner-1", "LISTING", "l-1", StoragePurpose.LISTING_IMAGE));
        assertEquals(StorageErrorCode.STORAGE_INVALID_PURPOSE.getCode(), ex.getCode());
        verify(s3Client, never()).copyObject(any(CopyObjectRequest.class));
    }

    @Test
    void copyObject_s3CopyFails_throwsException() {
        StorageObject source = StorageObject.builder()
                .id("src-err")
                .originalName("photo.jpg")
                .objectKey("listing_image/owner-1/src-err.jpg")
                .bucketName("test-bucket")
                .contentType("image/jpeg")
                .sizeBytes(1024L)
                .ownerId("owner-1")
                .purpose(StoragePurpose.LISTING_IMAGE)
                .status(StorageStatus.READY)
                .build();
        source.setActive(true);
        when(repository.findById("src-err")).thenReturn(Optional.of(source));
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenThrow(SdkException.create("S3 error", new RuntimeException()));

        AppException ex = assertThrows(AppException.class, () ->
                storageService.copyObject("src-err", "owner-1", "LISTING", "l-1", StoragePurpose.LISTING_IMAGE));
        assertEquals(StorageErrorCode.STORAGE_PROVIDER_ERROR.getCode(), ex.getCode());
        verify(repository, never()).save(any(StorageObject.class));
    }

    @Test
    void copyObject_repoSaveFails_cleansUpS3Object() {
        StorageObject source = StorageObject.builder()
                .id("src-cleanup")
                .originalName("photo.jpg")
                .objectKey("listing_image/owner-1/src-cleanup.jpg")
                .bucketName("test-bucket")
                .contentType("image/jpeg")
                .sizeBytes(1024L)
                .ownerId("owner-1")
                .purpose(StoragePurpose.LISTING_IMAGE)
                .status(StorageStatus.READY)
                .build();
        source.setActive(true);
        when(repository.findById("src-cleanup")).thenReturn(Optional.of(source));
        when(repository.save(any(StorageObject.class))).thenThrow(new RuntimeException("DB save failed"));

        assertThrows(RuntimeException.class, () ->
                storageService.copyObject("src-cleanup", "owner-1", "LISTING", "l-1", StoragePurpose.LISTING_IMAGE));

        verify(s3Client).copyObject(any(CopyObjectRequest.class));
        ArgumentCaptor<DeleteObjectRequest> deleteCaptor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(deleteCaptor.capture());
        assertTrue(deleteCaptor.getValue().key().startsWith("listing_image/owner-1/"));
    }
}
