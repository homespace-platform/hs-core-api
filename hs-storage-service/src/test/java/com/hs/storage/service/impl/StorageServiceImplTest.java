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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        lenient().when(s3Client.putObject(any(software.amazon.awssdk.services.s3.model.PutObjectRequest.class),
                any(software.amazon.awssdk.core.sync.RequestBody.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.PutObjectResponse.builder().versionId("uploaded-version").build());
        lenient().when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.CopyObjectResponse.builder().versionId("copied-version").build());
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void retainedBusinessEvidenceCannotBeDeletedEvenByOwner() {
        StorageObject object=StorageObject.builder().id("proof").ownerId("test-user-id").status(StorageStatus.READY).build();
        object.setActive(true);
        when(repository.findById("proof")).thenReturn(Optional.of(object));
        var checker=mock(com.hs.storage.service.StorageAccessChecker.class);
        storageService.setAccessCheckers(java.util.List.of(checker));
        when(checker.preventsDeletion(object)).thenReturn(true);
        assertThrows(AppException.class,()->storageService.delete("proof"));
        assertEquals(StorageStatus.READY,object.getStatus());verify(repository,never()).save(any());
        when(checker.preventsDeletion(object)).thenReturn(false);
        storageService.delete("proof");assertEquals(StorageStatus.DELETED,object.getStatus());
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

    @Test void aClientCannotUploadOrForgeAnOfficialPaymentReceipt() {
        var request=new CreateUploadRequest("forged.pdf","application/pdf",100L,StoragePurpose.PAYMENT_RECEIPT,StorageVisibility.PRIVATE,"PAYMENT_RECEIPT","r");
        assertThrows(AppException.class,()->storageService.createUpload(request));
        verifyNoInteractions(s3Client,presigner);verify(repository,never()).save(any());
    }

    @Test void generatedReceiptRequiresPdfBytesAndPrivateStorage() {
        assertThrows(AppException.class,()->storageService.uploadDirect("not a PDF".getBytes(StandardCharsets.US_ASCII),"r.pdf","application/pdf",StoragePurpose.PAYMENT_RECEIPT,"PAYMENT_RECEIPT","r",StorageVisibility.PRIVATE,"SYSTEM"));
        verifyNoInteractions(s3Client);verify(repository,never()).save(any());
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
                .bucketName("legacy-bucket")
                .s3VersionId("legacy-version")
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
        assertEquals("legacy-bucket", copyCaptor.getValue().sourceBucket());
        assertEquals("listing_image/owner-1/src-123.jpg", copyCaptor.getValue().sourceKey());
        assertEquals("legacy-version", copyCaptor.getValue().sourceVersionId());
        assertEquals("test-bucket", copyCaptor.getValue().destinationBucket());
        assertTrue(copyCaptor.getValue().destinationKey().startsWith("public/listing_image/owner-2/"));
        assertTrue(copyCaptor.getValue().destinationKey().endsWith(".jpg"));
        assertEquals("copied-version", response.s3VersionId());
        assertEquals(StorageVisibility.PUBLIC, response.visibility());
        // Source record keeps its persisted legacy location; it is never relabeled or re-keyed.
        assertEquals("listing_image/owner-1/src-123.jpg", source.getObjectKey());
        assertEquals("legacy-bucket", source.getBucketName());
        assertEquals("legacy-version", source.getS3VersionId());
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
    void copyObject_repoSaveFails_retainsVersionForAdminCleanup() {
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
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @CsvSource({
            "USER_AVATAR,image/jpeg,a.jpg", "LISTING_IMAGE,image/png,a.png", "LISTING_VIDEO,video/mp4,a.mp4",
            "BRANCH_COVER_IMAGE,image/webp,a.webp", "NEWS_IMAGE,image/jpeg,a.jpg"})
    void createUpload_publicPurposesUsePublicKeyAndVisibilityEvenWhenPrivateRequested(
            StoragePurpose purpose, String contentType, String fileName) throws Exception {
        var saved = createUploadAndCapture(fileName, contentType, purpose, StorageVisibility.PRIVATE, null, null);
        assertEquals(StorageVisibility.PUBLIC, saved.getVisibility());
        assertTrue(saved.getObjectKey().startsWith(
                "public/" + purpose.name().toLowerCase(java.util.Locale.ROOT) + "/test-user-id/" + saved.getId()));
        assertTrue(saved.getObjectKey().endsWith("." + fileName.substring(2)));
    }

    @ParameterizedTest
    @CsvSource({
            "CONTRACT_DOCUMENT,PUBLIC", "CONTRACT_DOCUMENT,AUTHENTICATED", "SIGNATURE_PREPARED_DOCUMENT,PUBLIC",
            "SIGNATURE_PREPARED_DOCUMENT,AUTHENTICATED", "IDENTITY_DOCUMENT,PUBLIC", "IDENTITY_DOCUMENT,AUTHENTICATED",
            "PAYMENT_PROOF,PUBLIC", "PAYMENT_PROOF,AUTHENTICATED", "GENERAL,PUBLIC", "GENERAL,AUTHENTICATED",
            "CHAT_ATTACHMENT,PUBLIC"})
    void createUpload_sensitiveOrGenericPurposesCannotBecomePublicOrAuthenticatedBySuppliedVisibility(
            StoragePurpose purpose, StorageVisibility requested) throws Exception {
        var saved = createUploadAndCapture("a.pdf", "application/pdf", purpose, requested, null, null);
        assertEquals(StorageVisibility.PRIVATE, saved.getVisibility());
        assertTrue(saved.getObjectKey().startsWith(
                "private/" + purpose.name().toLowerCase(java.util.Locale.ROOT) + "/test-user-id/"));
    }

    @Test
    void createUpload_chatAttachmentKeepsAuthenticatedApiBehaviorWithPrivateKey() throws Exception {
        var saved = createUploadAndCapture("a.pdf", "application/pdf", StoragePurpose.CHAT_ATTACHMENT,
                StorageVisibility.AUTHENTICATED, null, null);
        assertEquals(StorageVisibility.AUTHENTICATED, saved.getVisibility());
        assertTrue(saved.getObjectKey().startsWith("private/chat_attachment/test-user-id/"));
    }

    @ParameterizedTest
    @CsvSource({
            "LISTING,image/jpeg,a.jpg,LISTING_IMAGE", "LISTING,video/webm,a.webm,LISTING_VIDEO",
            "BRANCH,image/png,a.png,BRANCH_COVER_IMAGE", "PROPERTY_BRANCH,image/webp,a.webp,BRANCH_COVER_IMAGE"})
    void createUpload_generalWithListingOrBranchReferenceResolvesPurposeBeforeClassification(
            String referenceType, String contentType, String fileName, StoragePurpose expected) throws Exception {
        var saved = createUploadAndCapture(
                fileName, contentType, StoragePurpose.GENERAL, StorageVisibility.PUBLIC, referenceType, "ref-1");
        assertEquals(expected, saved.getPurpose());
        assertEquals(StorageVisibility.PUBLIC, saved.getVisibility());
        assertTrue(saved.getObjectKey().startsWith("public/" + expected.name().toLowerCase(java.util.Locale.ROOT) + "/"));
    }

    @Test
    void uploadDirect_usesPurposeLayoutAndIgnoresSuppliedVisibility() {
        when(repository.save(any(StorageObject.class))).thenAnswer(invocation -> invocation.getArgument(0));
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

        var avatar = storageService.uploadDirect(jpeg, "me.jpg", "image/jpeg", StoragePurpose.USER_AVATAR,
                null, null, StorageVisibility.PRIVATE, "user-9");
        assertEquals(StorageVisibility.PUBLIC, avatar.visibility());
        var proof = storageService.uploadDirect(jpeg, "p.jpg", "image/jpeg", StoragePurpose.PAYMENT_PROOF,
                "PAYMENT_REQUEST", "r1", StorageVisibility.PUBLIC, "user-9");
        assertEquals(StorageVisibility.PRIVATE, proof.visibility());

        var objects = ArgumentCaptor.forClass(StorageObject.class);
        verify(repository, times(2)).save(objects.capture());
        assertTrue(objects.getAllValues().get(0).getObjectKey().startsWith("public/user_avatar/user-9/"));
        assertTrue(objects.getAllValues().get(1).getObjectKey().startsWith("private/payment_proof/user-9/"));
        var puts = ArgumentCaptor.forClass(software.amazon.awssdk.services.s3.model.PutObjectRequest.class);
        verify(s3Client, times(2)).putObject(puts.capture(), any(software.amazon.awssdk.core.sync.RequestBody.class));
        assertEquals(objects.getAllValues().get(0).getObjectKey(), puts.getAllValues().get(0).key());
        assertEquals(objects.getAllValues().get(1).getObjectKey(), puts.getAllValues().get(1).key());
    }

    private StorageObject createUploadAndCapture(String fileName, String contentType, StoragePurpose purpose,
            StorageVisibility visibility, String referenceType, String referenceId) throws Exception {
        var presigned = mock(PresignedPutObjectRequest.class);
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
        when(presigned.url()).thenReturn(URI.create("https://example.com/upload").toURL());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var response = storageService.createUpload(new CreateUploadRequest(
                fileName, contentType, 1024L, purpose, visibility, referenceType, referenceId));
        var captor = ArgumentCaptor.forClass(StorageObject.class);
        verify(repository).save(captor.capture());
        assertEquals(captor.getValue().getObjectKey(), response.objectKey());
        return captor.getValue();
    }
}
