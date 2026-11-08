package com.hs.contract.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.client.SmartCaClient;
import com.hs.contract.client.SmartCaClient.SmartCaStatusResult;
import com.hs.contract.config.SmartCaProperties;
import com.hs.contract.dto.signature.InitiateSignatureRequest;
import com.hs.contract.model.Contract;
import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.SignatureRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SmartCaPollingOutcomeTest {
    @Mock SignatureRequestRepository requestRepository;
    @Mock ContractRepository contractRepository;
    @Mock SmartCaClient smartCaClient;
    @Mock SmartCaProperties properties;
    @Mock PlatformTransactionManager transactionManager;
    @InjectMocks SmartCaSignatureServiceImpl service;
    SignatureRequest request;

    @BeforeEach void setup() {
        request = SignatureRequest.builder().id("request").contractId("contract").docId("doc")
                .providerTransactionId("transaction").signerRole(SignerRole.LANDLORD)
                .status(SignatureRequestStatus.PENDING_USER_CONFIRMATION)
                .expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private void transactions() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @ParameterizedTest
    @CsvSource({"LANDLORD,REJECTED", "LANDLORD,EXPIRED", "LANDLORD,FAILED",
            "TENANT,REJECTED", "TENANT,EXPIRED", "TENANT,FAILED"})
    void terminalResultStopsWaitingAndOnlyReopensTheCorrectParty(SignerRole role, String outcome) {
        transactions(); request.setSignerRole(role);
        var contract = Contract.builder().id("contract").status(role == SignerRole.LANDLORD
                ? ContractStatus.LANDLORD_SIGNATURE_PENDING : ContractStatus.TENANT_SIGNATURE_PENDING).build();
        when(requestRepository.findById("request")).thenReturn(Optional.of(request));
        when(contractRepository.findById("contract")).thenReturn(Optional.of(contract));
        when(smartCaClient.getSignatureStatus("transaction", "doc")).thenReturn(result(outcome, null));
        service.processPendingRequest("request");
        assertEquals(SignatureRequestStatus.valueOf(outcome), request.getStatus());
        assertNotNull(request.getFailedAt()); assertNull(request.getProviderSignatureValue());
        assertEquals(role == SignerRole.LANDLORD ? ContractStatus.DRAFT : ContractStatus.TENANT_SIGNATURE_PENDING, contract.getStatus());
        assertNull(contract.getSignedAt()); verify(requestRepository).save(request);
    }

    @Test void successfulProviderSignatureStillMovesToEmbedding() {
        transactions();
        when(requestRepository.findById("request")).thenReturn(Optional.of(request));
        when(smartCaClient.getSignatureStatus("transaction", "doc")).thenReturn(result("SIGNED", "test-signature"));
        service.processPendingRequest("request");
        assertEquals(SignatureRequestStatus.PROVIDER_SIGNED, request.getStatus());
        assertEquals("test-signature", request.getProviderSignatureValue());
        verifyNoInteractions(contractRepository);
    }

    @ParameterizedTest @ValueSource(strings = {"CREATED", "PENDING_USER_CONFIRMATION"})
    void pollingErrorsAfterDeadlineBecomeUnknownRatherThanWaitingForeverOrAllowingResigning(String state) {
        transactions(); request.setStatus(SignatureRequestStatus.valueOf(state)); request.setExpiresAt(Instant.now().minusSeconds(1));
        when(requestRepository.findById("request")).thenReturn(Optional.of(request));
        when(requestRepository.findByIdForUpdate("request")).thenReturn(Optional.of(request));
        when(smartCaClient.getSignatureStatus("transaction", "doc"))
                .thenThrow(new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE));
        service.processPendingRequest("request");
        assertEquals(SignatureRequestStatus.FAILED, request.getStatus());
        assertEquals("PROVIDER_STATE_UNKNOWN", request.getFailureCode());
        assertNotNull(request.getFailedAt()); verifyNoInteractions(contractRepository);
    }

    @Test void transientPollErrorBeforeDeadlineKeepsTheExistingRequest() {
        transactions();
        when(requestRepository.findById("request")).thenReturn(Optional.of(request));
        when(requestRepository.findByIdForUpdate("request")).thenReturn(Optional.of(request));
        when(smartCaClient.getSignatureStatus("transaction", "doc"))
                .thenThrow(new AppException(ContractErrorCode.SIGNATURE_PROVIDER_UNAVAILABLE));
        service.processPendingRequest("request");
        assertEquals(SignatureRequestStatus.PENDING_USER_CONFIRMATION, request.getStatus());
        assertNotNull(request.getLastProviderCheckedAt()); verifyNoInteractions(contractRepository);
    }

    @Test void latePollFailureCannotOverwriteAConcurrentlyReceivedSignature() {
        transactions();
        var signed = SignatureRequest.builder().status(SignatureRequestStatus.PROVIDER_SIGNED).providerSignatureValue("test-signature").build();
        request.setExpiresAt(Instant.now().minusSeconds(1));
        when(requestRepository.findById("request")).thenReturn(Optional.of(request));
        when(requestRepository.findByIdForUpdate("request")).thenReturn(Optional.of(signed));
        when(smartCaClient.getSignatureStatus("transaction", "doc")).thenThrow(new IllegalStateException("poll failed"));
        service.processPendingRequest("request");
        assertEquals(SignatureRequestStatus.PROVIDER_SIGNED, signed.getStatus());
        verify(requestRepository, never()).save(any());
    }

    @Test void directInitiationCannotBypassTheUnknownProviderStateRetryGuard() {
        ReflectionTestUtils.setField(service, "signatureMode", "SMARTCA");
        when(properties.isEnabled()).thenReturn(true);
        var contract = Contract.builder().id("contract").landlordId("landlord").tenantId("tenant")
                .status(ContractStatus.TENANT_SIGNATURE_PENDING).build();
        request.setStatus(SignatureRequestStatus.FAILED); request.setFailureCode("PROVIDER_STATE_UNKNOWN");
        when(contractRepository.findById("contract")).thenReturn(Optional.of(contract));
        when(requestRepository.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc("contract", SignerRole.TENANT))
                .thenReturn(Optional.of(request));
        var input = new InitiateSignatureRequest(); input.setConsent(true); input.setCertificateSerial("certificate");
        assertEquals(ContractErrorCode.SIGNATURE_RETRY_NOT_ALLOWED.getCode(),
                assertThrows(AppException.class, () -> service.initiateSignature("contract", "tenant", input)).getCode());
        verifyNoInteractions(smartCaClient); verify(requestRepository, never()).save(any());
    }

    private SmartCaStatusResult result(String status, String signature) {
        return new SmartCaStatusResult("transaction", "provider", status, signature, null, null, null, null, null);
    }
}
