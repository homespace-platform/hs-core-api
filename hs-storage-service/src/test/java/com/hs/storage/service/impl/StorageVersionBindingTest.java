package com.hs.storage.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.dto.request.CompleteUploadRequest;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageAccessChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StorageVersionBindingTest {
    @Mock StorageObjectRepository repository;
    @Mock StorageProperties properties;
    @Mock S3Client s3;
    @Mock S3Presigner presigner;
    StorageServiceImpl service;
    final byte[] pdf = "%PDF-1.7 signed bytes".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach void setup() {
        service = new StorageServiceImpl(repository, properties, s3, presigner);
        UserContextHolder.set(new UserContext("owner", "owner@example.com"));
        lenient().when(properties.bucket()).thenReturn("bucket");
        lenient().when(properties.region()).thenReturn("ap-southeast-1");
        lenient().when(properties.downloadUrlDuration()).thenReturn(Duration.ofMinutes(5));
        lenient().when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach void clear() { UserContextHolder.clear(); }

    @Test void directUploadPinsVersionReturnedByPutWithoutHeadLatest() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("created-version").build());
        var result = service.uploadDirect(pdf, "signed.pdf", "application/pdf", StoragePurpose.CONTRACT_DOCUMENT,
                "CONTRACT", "contract", StorageVisibility.PRIVATE);
        assertEquals("created-version", result.s3VersionId());
        var saved = ArgumentCaptor.forClass(StorageObject.class);
        verify(repository).save(saved.capture());
        assertEquals("created-version", saved.getValue().getS3VersionId());
        verify(s3, never()).headObject(any(HeadObjectRequest.class));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "null"})
    void newUploadRejectsMissingOrUnversionedWrite(String version) {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId(version).build());
        AppException failure = assertThrows(AppException.class, () -> service.uploadDirect(pdf, "signed.pdf",
                "application/pdf", StoragePurpose.CONTRACT_DOCUMENT, "CONTRACT", "contract", StorageVisibility.PRIVATE));
        assertEquals(StorageErrorCode.STORAGE_VERSIONING_REQUIRED.getCode(), failure.getCode());
        verify(repository, never()).save(any());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "null"})
    void completeRejectsUnversionedHeadWithoutInspectingLatest(String version) {
        StorageObject object = object(StorageStatus.PENDING, null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .versionId(version).contentLength((long) pdf.length).contentType("application/pdf").build());
        AppException failure = assertThrows(AppException.class, () -> service.completeUpload("file", new CompleteUploadRequest(null)));
        assertEquals(StorageErrorCode.STORAGE_VERSIONING_REQUIRED.getCode(), failure.getCode());
        assertEquals(StorageStatus.PENDING, object.getStatus());
        verify(s3, never()).getObject(any(GetObjectRequest.class));
        verify(repository, never()).save(any());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "null"})
    void copyRejectsUnversionedDestination(String version) {
        object(StorageStatus.READY, "source-version");
        when(s3.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().versionId(version).build());
        AppException failure = assertThrows(AppException.class, () -> service.copyObject("file", "owner", "CONTRACT",
                "contract", StoragePurpose.CONTRACT_DOCUMENT));
        assertEquals(StorageErrorCode.STORAGE_VERSIONING_REQUIRED.getCode(), failure.getCode());
        verify(repository, never()).save(any());
    }

    @Test void completeInspectsExactlyHeadVersionEvenWhenLatestCouldChange() {
        StorageObject object = object(StorageStatus.PENDING, null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) pdf.length).contentType("application/pdf").versionId("head-version").build());
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdf, (long) pdf.length));
        assertEquals("head-version", service.completeUpload("file", new CompleteUploadRequest(null)).s3VersionId());
        var read = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(read.capture());
        assertEquals("head-version", read.getValue().versionId());
        assertEquals("bytes=0-63", read.getValue().range());
        assertEquals("head-version", object.getS3VersionId());
    }

    @Test void completeCannotBecomeReadyWhenMagicReadFails() {
        StorageObject object = object(StorageStatus.PENDING, null);
        when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                .contentLength((long) pdf.length).contentType("application/pdf").versionId("v1").build());
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(503).build());
        assertThrows(AppException.class, () -> service.completeUpload("file", new CompleteUploadRequest(null)));
        assertEquals(StorageStatus.PENDING, object.getStatus());
        assertNull(object.getS3VersionId());
        verify(repository, never()).save(any());
    }

    @Test void repeatedCompleteNeverRebindsReadyObjectToLatest() {
        object(StorageStatus.READY, "original-version");
        assertEquals("original-version", service.completeUpload("file", new CompleteUploadRequest(null)).s3VersionId());
        verifyNoInteractions(s3);
        verify(repository, never()).save(any());
    }

    @Test void copyPinsSourceAndStoresDifferentDestinationVersion() {
        object(StorageStatus.READY, "source-version");
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().versionId("destination-version").build());
        var copy = service.copyObject("file", "another-owner", "CONTRACT", "another-contract", StoragePurpose.CONTRACT_DOCUMENT);
        assertNotEquals("file", copy.id());
        assertEquals("destination-version", copy.s3VersionId());
        var request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3).copyObject(request.capture());
        assertEquals("source-version", request.getValue().sourceVersionId());
        assertNotEquals(request.getValue().sourceKey(), request.getValue().destinationKey());
    }

    @Test void directReadPreservesExplicitLegacyNullVersion() {
        object(StorageStatus.READY, "null");
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), pdf));
        assertArrayEquals(pdf, service.downloadDirect("file"));
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObjectAsBytes(request.capture());
        assertEquals("null", request.getValue().versionId());
    }

    @Test void presignedReadPinsVersionRatherThanLatest() throws Exception {
        object(StorageStatus.READY, "fixed-version");
        var signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(URI.create("https://example.test/file").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(signed);
        service.createDownloadUrl("file");
        var request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertEquals("fixed-version", request.getValue().getObjectRequest().versionId());
    }

    @Test void evidenceCannotBypassVerifierThroughStorageUrlsButInternalReadRemainsAvailable() {
        StorageObject object = object(StorageStatus.READY, "fixed-version");
        object.setVisibility(StorageVisibility.PUBLIC);
        StorageAccessChecker checker = mock(StorageAccessChecker.class);
        when(checker.requiresVerifiedAccess(object)).thenReturn(true);
        service.setAccessCheckers(List.of(checker));
        assertThrows(AppException.class, () -> service.createViewUrl("file"));
        assertThrows(AppException.class, () -> service.createDownloadUrl("file"));
        assertThrows(AppException.class, () -> service.getOwnedPublicUrl("file", StoragePurpose.CONTRACT_DOCUMENT));
        verifyNoInteractions(presigner);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), pdf));
        assertArrayEquals(pdf, service.downloadDirect("file"));
    }

    @Test void boundedReadUsesPinnedVersionAndEnforcesActualBytesDespiteSmallDeclaredSize() {
        object(StorageStatus.READY, "v1");
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdf, 1L));
        AppException failure = assertThrows(AppException.class, () -> service.downloadDirect("file", 5));
        assertEquals(StorageErrorCode.STORAGE_FILE_TOO_LARGE.getCode(), failure.getCode());
        var request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3).getObject(request.capture());
        assertEquals("v1", request.getValue().versionId());
    }

    @Test void boundedReadReturnsOnlyVerifiedReadBytesAtLimit() {
        object(StorageStatus.READY, "v1");
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdf, (long) pdf.length));
        assertArrayEquals(pdf, service.downloadDirect("file", pdf.length));
    }

    @Test void boundedReadRejectsLargeDeclaredLengthBeforeReadingBody() {
        object(StorageStatus.READY, "v1");
        when(s3.getObject(any(GetObjectRequest.class))).thenReturn(stream(pdf, 100_000L));
        AppException failure = assertThrows(AppException.class, () -> service.downloadDirect("file", 10));
        assertEquals(StorageErrorCode.STORAGE_FILE_TOO_LARGE.getCode(), failure.getCode());
    }

    @Test void missingPinnedVersionIsReportedAsMissingForBothInternalReads() {
        object(StorageStatus.READY, "deleted-version");
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(404).build());
        assertEquals(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND.getCode(),
                assertThrows(AppException.class, () -> service.downloadDirect("file", 100)).getCode());
        assertEquals(StorageErrorCode.STORAGE_OBJECT_NOT_FOUND.getCode(),
                assertThrows(AppException.class, () -> service.downloadDirect("file")).getCode());
    }

    @Test void versionedOrphanIsRetainedWithoutDeletingAnUnrelatedLatestOrRequiringPermanentDeleteRights() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().versionId("created-version").build());
        when(repository.save(any())).thenThrow(new IllegalStateException("DB unavailable"));
        assertThrows(IllegalStateException.class, () -> service.uploadDirect(pdf, "signed.pdf", "application/pdf",
                StoragePurpose.CONTRACT_DOCUMENT, "CONTRACT", "contract", StorageVisibility.PRIVATE));
        verify(s3, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    private StorageObject object(StorageStatus status, String version) {
        StorageObject object = StorageObject.builder().id("file").ownerId("owner").originalName("signed.pdf")
                .bucketName("bucket").objectKey("contract_document/owner/file.pdf").s3VersionId(version)
                .contentType("application/pdf").sizeBytes((long) pdf.length).extension("pdf")
                .purpose(StoragePurpose.CONTRACT_DOCUMENT).visibility(StorageVisibility.PRIVATE).status(status).build();
        object.setActive(true);
        when(repository.findById("file")).thenReturn(Optional.of(object));
        return object;
    }

    private ResponseInputStream<GetObjectResponse> stream(byte[] data, Long contentLength) {
        return new ResponseInputStream<>(GetObjectResponse.builder().contentLength(contentLength).build(),
                new ByteArrayInputStream(data));
    }
}
