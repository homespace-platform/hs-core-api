package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.CreateUploadSessionResponse;
import com.hs.payment.dto.UploadSessionStatusResponse;
import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.repository.PaymentEventRepository;
import com.hs.payment.repository.PaymentEvidenceRepository;
import com.hs.payment.repository.PaymentProofUploadSessionRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentProofUploadSessionServiceTest {

    @Mock
    private PaymentProofUploadSessionRepository uploadSessionRepository;

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private StorageService storageService;

    @InjectMocks
    private PaymentProofUploadSessionService sessionService;

    private PaymentRequest paymentRequest;

    @BeforeEach
    void setUp() {
        paymentRequest = PaymentRequest.builder()
                .id("pay-123")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();
    }

    @Test
    @DisplayName("1. Tenant hop le tao duoc upload session va raw token khong duoc luu trong DB")
    void createSession_ValidTenant_Success() {
        when(paymentRequestRepository.findById("pay-123")).thenReturn(Optional.of(paymentRequest));
        when(uploadSessionRepository.findByPaymentRequestIdAndTenantUserIdAndStatusIn(anyString(), anyString(), anyList()))
                .thenReturn(List.of());

        ArgumentCaptor<PaymentProofUploadSession> sessionCaptor = ArgumentCaptor.forClass(PaymentProofUploadSession.class);
        when(uploadSessionRepository.save(sessionCaptor.capture())).thenAnswer(invocation -> {
            PaymentProofUploadSession entity = invocation.getArgument(0);
            entity.setId("sess-1");
            return entity;
        });

        String baseUrl = "https://app.homespace.local";
        CreateUploadSessionResponse response = sessionService.createSession("pay-123", "tenant-1", baseUrl);

        assertNotNull(response);
        assertEquals("sess-1", response.sessionId());
        assertEquals(UploadSessionStatus.CREATED, response.status());
        assertTrue(response.uploadPath().startsWith("/u/payment-proof/"));
        assertTrue(response.uploadPageUrl().startsWith("https://app.homespace.local/u/payment-proof/"));

        // Extract raw token from response URL
        String rawToken = response.uploadPath().replace("/u/payment-proof/", "");
        assertFalse(rawToken.isBlank());

        // Verify entity saved in DB contains tokenHash, NEVER rawToken
        PaymentProofUploadSession savedEntity = sessionCaptor.getValue();
        assertNotNull(savedEntity.getTokenHash());
        assertNotEquals(rawToken, savedEntity.getTokenHash());
        assertEquals(sessionService.hashToken(rawToken), savedEntity.getTokenHash());
        assertEquals("pay-123", savedEntity.getPaymentRequestId());
        assertEquals("tenant-1", savedEntity.getTenantUserId());
    }

    @Test
    @DisplayName("2. User khac khong the tao session cho payment request do")
    void createSession_WrongUser_ThrowsForbidden() {
        when(paymentRequestRepository.findById("pay-123")).thenReturn(Optional.of(paymentRequest));

        AppException ex = assertThrows(AppException.class, () ->
                sessionService.createSession("pay-123", "other-user", "http://localhost:8080"));
        assertEquals(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN.getCode(), ex.getCode());
        verify(uploadSessionRepository, never()).save(any());
    }

    @Test
    @DisplayName("3. Token sai tra ve null (khong tiet lo thong tin)")
    void findValidSessionByRawToken_InvalidToken_ReturnsNull() {
        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        PaymentProofUploadSession result = sessionService.findValidSessionByRawToken("invalid-raw-token-12345");
        assertNull(result);
    }

    @Test
    @DisplayName("4. Token het han tra ve null va chuyen status thanh EXPIRED")
    void findValidSessionByRawToken_ExpiredToken_ReturnsNullAndExpires() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-expired")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .tokenHash("some-hash")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(Instant.now().minusSeconds(60)) // Expired 1 min ago
                .build();

        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(session));

        PaymentProofUploadSession result = sessionService.findValidSessionByRawToken("token-expired");
        assertNull(result);
        assertEquals(UploadSessionStatus.EXPIRED, session.getStatus());
        verify(uploadSessionRepository).save(session);
    }

    @Test
    @DisplayName("5. Token da CONSUMED khong the upload lai")
    void findValidSessionByRawToken_AlreadyConsumed_ReturnsNull() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-consumed")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .tokenHash("some-hash")
                .status(UploadSessionStatus.CONSUMED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(session));

        PaymentProofUploadSession result = sessionService.findValidSessionByRawToken("token-consumed");
        assertNull(result);
        verify(uploadSessionRepository, never()).save(session);
    }

    @Test
    @DisplayName("6. File sai loai hoac qua 15MB bi tu choi")
    void handleMobileUpload_InvalidFile_ThrowsException() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-valid")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .tokenHash("some-hash")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(paymentRequestRepository.findByIdForUpdate("pay-123")).thenReturn(Optional.of(paymentRequest));

        byte[] fakeBytes = "content".getBytes(StandardCharsets.UTF_8);

        // Disallowed content type (e.g. text/html, image/svg+xml)
        AppException exHtml = assertThrows(AppException.class, () ->
                sessionService.handleMobileUpload("valid-token", fakeBytes, "attack.html", "text/html", fakeBytes.length));
        assertEquals(PaymentErrorCode.PAYMENT_PROOF_INVALID.getCode(), exHtml.getCode());

        // Oversized file (> 15MB)
        AppException exSize = assertThrows(AppException.class, () ->
                sessionService.handleMobileUpload("valid-token", fakeBytes, "large.jpg", "image/jpeg", 16L * 1024 * 1024));
        assertEquals(PaymentErrorCode.PAYMENT_PROOF_INVALID.getCode(), exSize.getCode());
    }

    @Test
    @DisplayName("7. Upload thanh cong tao PaymentEvidence, chuyen PaymentRequest sang TRANSFER_REPORTED va session sang CONSUMED")
    void handleMobileUpload_Success() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-valid")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .tokenHash("some-hash")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(uploadSessionRepository.save(any(PaymentProofUploadSession.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentRequestRepository.findByIdForUpdate("pay-123")).thenReturn(Optional.of(paymentRequest));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentEvidenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(paymentEventRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StorageObjectResponse mockStorageResp = new StorageObjectResponse(
                "storage-uuid-999",
                "proof.png",
                "image/png",
                1024L,
                "checksum",
                "png",
                "tenant-1",
                "PAYMENT_REQUEST",
                "pay-123",
                StoragePurpose.PAYMENT_PROOF,
                StorageVisibility.PRIVATE,
                StorageStatus.READY,
                Instant.now(),
                null
        );

        when(storageService.uploadDirect(
                any(byte[].class),
                eq("receipt.png"),
                eq("image/png"),
                eq(StoragePurpose.PAYMENT_PROOF),
                eq("PAYMENT_REQUEST"),
                eq("pay-123"),
                eq(StorageVisibility.PRIVATE),
                eq("tenant-1")
        )).thenReturn(mockStorageResp);

        byte[] fileBytes = new byte[]{1, 2, 3, 4};
        PaymentProofUploadSession uploadedSession = sessionService.handleMobileUpload(
                "valid-token",
                fileBytes,
                "receipt.png",
                "image/png",
                4L
        );

        assertEquals(UploadSessionStatus.CONSUMED, uploadedSession.getStatus());
        assertEquals("storage-uuid-999", uploadedSession.getStorageId());
        assertEquals("receipt.png", uploadedSession.getOriginalFileName());
        assertEquals(4L, uploadedSession.getFileSize());
        assertNotNull(uploadedSession.getUploadedAt());
        assertNotNull(uploadedSession.getConsumedAt());

        // PaymentRequest repository is updated to TRANSFER_REPORTED!
        assertEquals(PaymentStatus.TRANSFER_REPORTED, paymentRequest.getStatus());
        verify(paymentRequestRepository).save(paymentRequest);
        verify(paymentEvidenceRepository).save(any());
        verify(paymentEventRepository).save(any());
    }

    @Test
    @DisplayName("7b. Reload/Re-POST khi session da CONSUMED va payment la TRANSFER_REPORTED tra ve session ma khong loi")
    void handleMobileUpload_IdempotentAlreadyConsumed() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-consumed")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .tokenHash("some-hash")
                .status(UploadSessionStatus.CONSUMED)
                .storageId("storage-uuid-999")
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        paymentRequest.setStatus(PaymentStatus.TRANSFER_REPORTED);
        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(paymentRequestRepository.findById("pay-123")).thenReturn(Optional.of(paymentRequest));

        PaymentProofUploadSession result = sessionService.handleMobileUpload(
                "valid-token",
                new byte[]{1, 2},
                "receipt.png",
                "image/png",
                2L
        );

        assertEquals(UploadSessionStatus.CONSUMED, result.getStatus());
        verify(storageService, never()).uploadDirect(any(), any(), any(), any(), any(), any(), any());
        verify(paymentEvidenceRepository, never()).save(any());
    }

    @Test
    @DisplayName("7c. Upload storage loi thi revert session ve CREATED va quang loi")
    void handleMobileUpload_StorageError_RevertsSessionToCreated() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-valid")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .tokenHash("some-hash")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(uploadSessionRepository.findByTokenHash(anyString())).thenReturn(Optional.of(session));
        when(paymentRequestRepository.findByIdForUpdate("pay-123")).thenReturn(Optional.of(paymentRequest));
        when(uploadSessionRepository.save(any(PaymentProofUploadSession.class))).thenAnswer(i -> i.getArgument(0));

        when(storageService.uploadDirect(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("S3 connection error"));

        assertThrows(RuntimeException.class, () -> sessionService.handleMobileUpload(
                "valid-token",
                new byte[]{1, 2},
                "receipt.png",
                "image/png",
                2L
        ));

        assertEquals(UploadSessionStatus.CREATED, session.getStatus());
        verify(paymentEvidenceRepository, never()).save(any());
        verify(paymentRequestRepository, never()).save(any());
    }

    @Test
    @DisplayName("8. Chi tenant so huu moi lay duoc status session")
    void getSessionStatus_OwnerAndOtherUser() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-1")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(uploadSessionRepository.findByIdAndPaymentRequestId("sess-1", "pay-123"))
                .thenReturn(Optional.of(session));

        // Tenant 1 can view
        UploadSessionStatusResponse res = sessionService.getSessionStatus("pay-123", "sess-1", "tenant-1");
        assertEquals("sess-1", res.sessionId());
        assertEquals(UploadSessionStatus.CREATED, res.status());

        // Other user cannot view
        AppException ex = assertThrows(AppException.class, () ->
                sessionService.getSessionStatus("pay-123", "sess-1", "hacker-user"));
        assertEquals(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("9. Consume session thanh cong va transaction-safe")
    void consumeSession_Success() {
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-uploaded")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .storageId("storage-proof-1")
                .status(UploadSessionStatus.UPLOADED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(uploadSessionRepository.findByIdAndPaymentRequestId("sess-uploaded", "pay-123"))
                .thenReturn(Optional.of(session));
        when(uploadSessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String storageId = sessionService.consumeSession("sess-uploaded", "pay-123", "tenant-1");
        assertEquals("storage-proof-1", storageId);
        assertEquals(UploadSessionStatus.CONSUMED, session.getStatus());
        assertNotNull(session.getConsumedAt());
    }

    @Test
    @DisplayName("10. Khong the consume session cua payment request khac hoac khong phai UPLOADED")
    void consumeSession_WrongStatusOrPaymentRequest_Fails() {
        when(uploadSessionRepository.findByIdAndPaymentRequestId("sess-other", "pay-123"))
                .thenReturn(Optional.empty());

        AppException exNotFound = assertThrows(AppException.class, () ->
                sessionService.consumeSession("sess-other", "pay-123", "tenant-1"));
        assertEquals(PaymentErrorCode.PROOF_SESSION_NOT_FOUND.getCode(), exNotFound.getCode());

        PaymentProofUploadSession sessionCreated = PaymentProofUploadSession.builder()
                .id("sess-created")
                .paymentRequestId("pay-123")
                .tenantUserId("tenant-1")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(uploadSessionRepository.findByIdAndPaymentRequestId("sess-created", "pay-123"))
                .thenReturn(Optional.of(sessionCreated));

        AppException exStatus = assertThrows(AppException.class, () ->
                sessionService.consumeSession("sess-created", "pay-123", "tenant-1"));
        assertEquals(PaymentErrorCode.PROOF_SESSION_INVALID.getCode(), exStatus.getCode());
    }
}
