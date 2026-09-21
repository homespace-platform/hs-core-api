package com.hs.payment.service;

import com.hs.payment.model.PaymentRequest;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentStorageAccessCheckerTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @InjectMocks
    private PaymentStorageAccessChecker accessChecker;

    private StorageObject paymentProofStorage;
    private PaymentRequest paymentRequest;

    @BeforeEach
    void setUp() {
        paymentProofStorage = StorageObject.builder()
                .id("storage-1")
                .purpose(StoragePurpose.PAYMENT_PROOF)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();

        paymentRequest = PaymentRequest.builder()
                .id("pay-1")
                .payerId("tenant-1")
                .payeeId("landlord-1")
                .build();
    }

    @Test
    @DisplayName("Payer can access payment proof")
    void payer_CanAccess_ReturnsTrue() {
        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(paymentRequest));
        assertTrue(accessChecker.canAccess("tenant-1", paymentProofStorage));
    }

    @Test
    @DisplayName("Payee (landlord) can access payment proof")
    void payee_CanAccess_ReturnsTrue() {
        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(paymentRequest));
        assertTrue(accessChecker.canAccess("landlord-1", paymentProofStorage));
    }

    @Test
    @DisplayName("Unrelated third party cannot access payment proof")
    void stranger_CannotAccess_ReturnsFalse() {
        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(paymentRequest));
        assertFalse(accessChecker.canAccess("stranger-user", paymentProofStorage));
    }

    @Test
    @DisplayName("Wrong purpose returns false")
    void wrongPurpose_ReturnsFalse() {
        StorageObject otherStorage = StorageObject.builder()
                .id("storage-2")
                .purpose(StoragePurpose.USER_AVATAR)
                .referenceType("PAYMENT_REQUEST")
                .referenceId("pay-1")
                .build();
        assertFalse(accessChecker.canAccess("tenant-1", otherStorage));
    }
}
