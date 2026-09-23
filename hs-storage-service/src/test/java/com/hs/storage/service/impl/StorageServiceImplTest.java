package com.hs.storage.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.storage.advice.entity.enums.StorageErrorCode;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

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
}
