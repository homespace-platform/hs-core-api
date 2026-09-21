package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.PaymentRequestResponse;
import com.hs.payment.dto.RejectReceiptRequest;
import com.hs.payment.dto.ReportTransferRequest;
import com.hs.payment.model.BankAccount;
import com.hs.payment.model.PaymentEvidence;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.BankAccountStatus;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.*;
import com.hs.payment.service.qr.VietQrProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;
    @Mock
    private PaymentLineItemRepository paymentLineItemRepository;
    @Mock
    private PaymentEvidenceRepository paymentEvidenceRepository;
    @Mock
    private PaymentEventRepository paymentEventRepository;
    @Mock
    private DepositRecordRepository depositRecordRepository;
    @Mock
    private BankAccountService bankAccountService;
    @Mock
    private VietQrProvider vietQrProvider;
    @Mock
    private TransferReferenceGenerator transferReferenceGenerator;
    @Mock
    private com.hs.storage.repository.StorageObjectRepository storageObjectRepository;
    @Mock
    private PaymentProofUploadSessionService paymentProofUploadSessionService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PaymentRequestService paymentRequestService;

    private BankAccount landlordAccount;
    private BankAccount tenantAccount;

    @BeforeEach
    void setUp() {
        landlordAccount = BankAccount.builder()
                .id("acc-landlord")
                .userId("landlord-1")
                .bankBin("970422")
                .bankCode("MB")
                .bankName("MB Bank")
                .accountNumber("0987654321")
                .accountHolderName("TRAN VAN CHU NHA")
                .defaultForIncomingPayments(true)
                .status(BankAccountStatus.ACTIVE)
                .build();

        tenantAccount = BankAccount.builder()
                .id("acc-tenant")
                .userId("tenant-1")
                .bankBin("970436")
                .bankCode("VCB")
                .bankName("Vietcombank")
                .accountNumber("1234567890")
                .accountHolderName("NGUYEN VAN NGUOI THUE")
                .defaultForRefunds(true)
                .status(BankAccountStatus.ACTIVE)
                .build();
    }

    @Test
    @DisplayName("createInitialPayment creates payment with VietQR and reference")
    void createInitialPayment_Success() {
        when(paymentRequestRepository.findByRentalRequestIdAndType("req-1", PaymentType.INITIAL))
                .thenReturn(Optional.empty());
        when(bankAccountService.getDefaultIncomingAccount("landlord-1")).thenReturn(landlordAccount);
        when(bankAccountService.getDefaultRefundAccount("tenant-1")).thenReturn(tenantAccount);
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HS12345678");
        when(vietQrProvider.generateQrImageUrl(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn("https://img.vietqr.io/image/970422-0987654321-compact2.png?amount=15000000&addInfo=HS12345678");

        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> {
            PaymentRequest p = i.getArgument(0);
            p.setId("pay-1");
            return p;
        });

        PaymentRequest result = paymentRequestService.createInitialPayment(
                "req-1",
                "listing-1",
                "tenant-1",
                "landlord-1",
                new BigDecimal("10000000"),
                BigDecimal.ZERO,
                new BigDecimal("5000000"),
                new BigDecimal("15000000"),
                "[]",
                "[]",
                Instant.now().plusSeconds(86400)
        );

        assertNotNull(result);
        assertEquals("HS12345678", result.getTransferReference());
        assertEquals(PaymentStatus.AWAITING_TRANSFER, result.getStatus());
        assertEquals(new BigDecimal("15000000"), result.getTotalAmount());
        verify(paymentLineItemRepository, atLeastOnce()).save(any());
        verify(paymentEventRepository, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("reportTransfer updates status to TRANSFER_REPORTED and saves evidence")
    void reportTransfer_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        com.hs.storage.model.StorageObject mockStorage = com.hs.storage.model.StorageObject.builder()
                .id("storage-1")
                .ownerId("tenant-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF)
                .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        when(storageObjectRepository.findById("storage-1")).thenReturn(Optional.of(mockStorage));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-1")
                .build();

        PaymentRequestResponse resp = paymentRequestService.reportTransfer("pay-1", "tenant-1", request);

        assertEquals(PaymentStatus.TRANSFER_REPORTED, payment.getStatus());
        verify(paymentEvidenceRepository).save(any(PaymentEvidence.class));
        verify(paymentEventRepository).save(any());
    }

    @Test
    @DisplayName("reportTransfer with valid evidenceUploadSessionId consumes session and transitions to TRANSFER_REPORTED")
    void reportTransfer_WithEvidenceUploadSessionId_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(paymentProofUploadSessionService.consumeSession("sess-123", "pay-1", "tenant-1"))
                .thenReturn("storage-from-session");

        ReportTransferRequest request = ReportTransferRequest.builder()
                .evidenceUploadSessionId("sess-123")
                .build();

        PaymentRequestResponse resp = paymentRequestService.reportTransfer("pay-1", "tenant-1", request);

        assertEquals(PaymentStatus.TRANSFER_REPORTED, payment.getStatus());
        verify(paymentProofUploadSessionService).consumeSession("sess-123", "pay-1", "tenant-1");
        verify(paymentEvidenceRepository).save(any(PaymentEvidence.class));
        verify(paymentEventRepository).save(any());
    }

    @Test
    @DisplayName("reportTransfer without proofStorageId throws PAYMENT_PROOF_REQUIRED")
    void reportTransfer_MissingProof_ThrowsException() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));

        ReportTransferRequest request = ReportTransferRequest.builder().build();

        AppException ex = assertThrows(AppException.class, () ->
                paymentRequestService.reportTransfer("pay-1", "tenant-1", request));
        assertEquals(PaymentErrorCode.PAYMENT_PROOF_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("reportTransfer with proof owned by different user throws PAYMENT_PROOF_INVALID")
    void reportTransfer_WrongOwnerProof_ThrowsException() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));

        com.hs.storage.model.StorageObject wrongOwnerStorage = com.hs.storage.model.StorageObject.builder()
                .id("storage-fake")
                .ownerId("hacker-user")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF)
                .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        when(storageObjectRepository.findById("storage-fake")).thenReturn(Optional.of(wrongOwnerStorage));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-fake")
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                paymentRequestService.reportTransfer("pay-1", "tenant-1", request));
        assertEquals(PaymentErrorCode.PAYMENT_PROOF_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("reportTransfer re-submission from REJECTED clears rejection info")
    void reportTransfer_ResubmitFromRejected_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.REJECTED)
                .rejectedAt(Instant.now())
                .rejectedReason("Chua thay bien dong so du")
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        com.hs.storage.model.StorageObject mockStorage = com.hs.storage.model.StorageObject.builder()
                .id("storage-2")
                .ownerId("tenant-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.PAYMENT_PROOF)
                .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        when(storageObjectRepository.findById("storage-2")).thenReturn(Optional.of(mockStorage));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-2")
                .build();

        paymentRequestService.reportTransfer("pay-1", "tenant-1", request);

        assertEquals(PaymentStatus.TRANSFER_REPORTED, payment.getStatus());
        assertNull(payment.getRejectedReason());
        assertNull(payment.getRejectedAt());
    }

    @Test
    @DisplayName("reportTransfer by unauthorized user throws exception")
    void reportTransfer_UnauthorizedUser_ThrowsException() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));

        ReportTransferRequest request = ReportTransferRequest.builder()
                .proofStorageId("storage-1")
                .build();

        AppException ex = assertThrows(AppException.class, () ->
                paymentRequestService.reportTransfer("pay-1", "other-user", request));
        assertEquals(PaymentErrorCode.PAYMENT_REQUEST_FORBIDDEN.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("confirmReceipt by landlord changes status to CONFIRMED and creates deposit record")
    void confirmReceipt_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .rentalRequestId("req-1")
                .listingId("listing-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.TRANSFER_REPORTED)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        PaymentRequestResponse resp = paymentRequestService.confirmReceipt("pay-1", "landlord-1");

        assertEquals(PaymentStatus.CONFIRMED, payment.getStatus());
        assertNotNull(payment.getConfirmedAt());
        assertNotNull(payment.getPayeeConfirmedAt());
        verify(paymentEventRepository).save(any());
    }

    @Test
    @DisplayName("rejectReceipt changes status to REJECTED")
    void rejectReceipt_Success() {
        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .status(PaymentStatus.TRANSFER_REPORTED)
                .totalAmount(new BigDecimal("15000000"))
                .build();

        when(paymentRequestRepository.findByIdForUpdate("pay-1")).thenReturn(Optional.of(payment));
        when(paymentRequestRepository.save(any(PaymentRequest.class))).thenAnswer(i -> i.getArgument(0));

        RejectReceiptRequest req = new RejectReceiptRequest("Chua thay tien vao tai khoan, vui long kiem tra lai");

        paymentRequestService.rejectReceipt("pay-1", "landlord-1", req);

        assertEquals(PaymentStatus.REJECTED, payment.getStatus());
        verify(paymentEventRepository).save(any());
    }
}
