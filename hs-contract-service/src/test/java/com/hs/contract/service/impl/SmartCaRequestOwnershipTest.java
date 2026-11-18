package com.hs.contract.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.config.SmartCaProperties;
import com.hs.contract.model.Contract;
import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.contract.model.constant.SignerRole;
import com.hs.contract.repository.ContractRepository;
import com.hs.contract.repository.SignatureRequestRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** A contract party may only see/refresh/retry the signature request of its own signer role. */
@ExtendWith(MockitoExtension.class)
class SmartCaRequestOwnershipTest {
    @Mock SignatureRequestRepository requestRepository;
    @Mock ContractRepository contractRepository;
    @Mock SmartCaProperties properties;
    @InjectMocks SmartCaSignatureServiceImpl service;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(service, "signatureMode", "SMARTCA");
        lenient().when(properties.isEnabled()).thenReturn(true);
        lenient().when(contractRepository.findById("contract")).thenReturn(Optional.of(
                Contract.builder().id("contract").landlordId("landlord").tenantId("tenant").build()));
        lenient().when(requestRepository.findById("landlord-request")).thenReturn(Optional.of(
                SignatureRequest.builder().id("landlord-request").contractId("contract").signerRole(SignerRole.LANDLORD)
                        .status(SignatureRequestStatus.FAILED).build()));
    }

    @Test void tenantCannotGetRefreshOrRetryTheLandlordsRequest() {
        for (Executable call : List.<Executable>of(
                () -> service.getSignatureRequest("contract", "landlord-request", "tenant"),
                () -> service.refreshSignatureRequest("contract", "landlord-request", "tenant"),
                () -> service.retrySignature("contract", "landlord-request", "tenant"))) {
            assertEquals(ContractErrorCode.SIGNATURE_REQUEST_NOT_FOUND.getCode(), assertThrows(AppException.class, call).getCode());
        }
    }

    @Test void landlordStillReadsItsOwnRequestAndStrangersAreForbidden() {
        assertEquals("landlord-request", service.getSignatureRequest("contract", "landlord-request", "landlord").getId());
        assertEquals(ContractErrorCode.CONTRACT_FORBIDDEN.getCode(),
                assertThrows(AppException.class, () -> service.getSignatureRequest("contract", "landlord-request", "stranger")).getCode());
    }
}
