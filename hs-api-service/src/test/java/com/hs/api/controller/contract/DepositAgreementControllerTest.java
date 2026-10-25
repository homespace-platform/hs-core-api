package com.hs.api.controller.contract;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.deposit.DepositAgreementService;
import com.hs.contract.dto.response.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.service.impl.ContractServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepositAgreementControllerTest {
    private final DepositAgreementService deposits=mock(DepositAgreementService.class);
    private final ContractServiceImpl contracts=mock(ContractServiceImpl.class);
    private final DepositAgreementController controller=new DepositAgreementController(deposits,contracts);

    @AfterEach void cleanup(){UserContextHolder.clear();}

    @Test void exposesExistingDepositAndReadyPdfUsingTheTransactionalScalarReadModel() {
        UserContextHolder.set(new UserContext("tenant",null));
        when(deposits.overview("rental","tenant")).thenReturn(new DepositAgreementService.Overview(true,true,null,"deposit-doc"));
        var contract=ContractResponse.builder().id("deposit-doc").agreementKind("DEPOSIT").status(ContractStatus.DRAFT).build();
        var pdf=ContractDocumentResponse.builder().id("pdf").documentType(ContractDocumentType.PDF)
            .status(DocumentGenerationStatus.READY).build();
        when(contracts.getContract("deposit-doc")).thenReturn(contract);
        when(contracts.getDocumentsByContract("deposit-doc")).thenReturn(List.of(pdf));
        var response=controller.view("rental").getResult();
        assertTrue(response.required());assertTrue(response.signingAllowed());assertSame(contract,response.agreement());
        assertEquals(List.of(pdf),response.documents());
        verify(deposits).overview("rental","tenant");verifyNoMoreInteractions(deposits);
    }
    @Test void anActuallyAbsentAgreementIsDifferentFromAReadFailure() {
        UserContextHolder.set(new UserContext("tenant",null));
        when(deposits.overview("rental","tenant")).thenReturn(new DepositAgreementService.Overview(true,true,null,null));
        var response=controller.view("rental").getResult();assertNull(response.agreement());assertTrue(response.documents().isEmpty());
        verifyNoInteractions(contracts);
        when(deposits.overview("rental","tenant")).thenThrow(new IllegalStateException("read failed"));
        assertThrows(IllegalStateException.class,()->controller.view("rental"));
    }
    @Test void unauthenticatedReaderDoesNotReachTheDocumentServices() {
        UserContextHolder.clear();assertThrows(AppException.class,()->controller.view("rental"));
        verifyNoInteractions(deposits,contracts);
    }
}
