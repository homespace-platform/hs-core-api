package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.RentalWorkflowPayloads;
import com.hs.contract.model.Contract;
import com.hs.contract.model.RentalWorkflowRecord;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.TerminationNotificationWriter;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.PaymentLineItemRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MutualTerminationServiceTest {
    final RentalWorkflowRecordRepository records = mock(RentalWorkflowRecordRepository.class);
    final ContractRepository contracts = mock(ContractRepository.class);
    final ContractRenewalRepository renewals = mock(ContractRenewalRepository.class);
    final ContractAmendmentRepository amendments = mock(ContractAmendmentRepository.class);
    final MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    final PaymentRequestRepository paymentRecords = mock(PaymentRequestRepository.class);
    final PaymentRequestService payments = mock(PaymentRequestService.class);
    final TerminationNotificationWriter notices = mock(TerminationNotificationWriter.class);
    final BillingTime time = mock(BillingTime.class);
    final Contract contract = Contract.builder().id("lease").rentalRequestId("rental")
        .landlordId("owner").tenantId("tenant").currentRevisionId("revision")
        .status(ContractStatus.EXPIRED).signedAt(Instant.parse("2026-10-01T00:00:00Z"))
        .mutualTerminationWorkflowId("rental:EARLY_EXIT").build();
    final RentalRequest rental = RentalRequest.builder().id("rental").ownerId("owner")
        .renterId("tenant").moveInDate(LocalDate.of(2026,10,1)).leaseMonths(1).build();
    final RentalWorkflowRecord workflow = new RentalWorkflowRecord();
    RentalWorkflowSupport support;
    MutualTerminationService service;

    @BeforeEach void setup() {
        when(time.now()).thenReturn(Instant.parse("2026-11-02T03:00:00Z"));
        when(time.today()).thenReturn(LocalDate.of(2026,11,2));
        workflow.setId("rental:EARLY_EXIT"); workflow.setRentalRequestId("rental");
        workflow.setKind("EARLY_EXIT"); workflow.setStatus("PROPOSED"); workflow.setRevision(4);
        workflow.setProposedBy("owner"); workflow.setSourceRevisionId("revision");
        support = spy(new RentalWorkflowSupport(records, mock(RentalRequestRepository.class), contracts,
            mock(ContractRevisionRepository.class), mock(StorageObjectRepository.class), paymentRecords,
            new ObjectMapper().findAndRegisterModules(), time));
        for (String actor : new String[]{"owner", "tenant"}) {
            doReturn(new RentalWorkflowSupport.Source(rental,contract,workflow,actor))
                .when(support).lock("rental",actor,"EARLY_EXIT");
        }
        service = new MutualTerminationService(support, renewals, amendments, invoices,
            mock(PaymentLineItemRepository.class), payments, mock(ComplaintCaseService.class));
        ReflectionTestUtils.setField(service,"terminationNotices",notices);
    }

    @ParameterizedTest @CsvSource({"owner,WITHDRAW", "tenant,REJECT"})
    void expiredLeaseStillOffersTheCorrectWayToDismissThePendingProposal(String actor,String action) {
        var view = service.get("rental",actor);
        assertTrue(view.allowedActions().contains(action));
        assertFalse(view.allowedActions().contains("ACCEPT"));
        assertFalse(view.allowedActions().contains("PROPOSE"));
        assertNotNull(view.blockedReason());
        assertEquals(4,workflow.getRevision());
        verifyNoInteractions(payments,records,notices);
    }

    @ParameterizedTest @CsvSource({"owner,WITHDRAW,WITHDRAWN", "tenant,REJECT,REJECTED"})
    void dismissingAnExpiredProposalKeepsTheLeaseAndMoneyUnchanged(String actor,String action,String status) {
        var result = service.act("rental",actor,action,new RentalWorkflowPayloads.Action(4L,"Không tiếp tục đề nghị chấm dứt cũ",false,false));
        assertEquals(status,result.status()); assertEquals(5,result.revision());
        assertNull(contract.getMutualTerminationWorkflowId());
        assertNull(contract.getAgreedEarlyEndExclusive());
        assertEquals(ContractStatus.EXPIRED,contract.getStatus());
        verifyNoInteractions(payments,invoices);
        verify(records).saveAndFlush(workflow);
    }

    @ParameterizedTest @CsvSource({"owner,WITHDRAW", "tenant,REJECT"})
    void staleDismissalCannotChangeTheProposalOrMoney(String actor,String action) {
        assertThrows(AppException.class,()->service.act("rental",actor,action,
            new RentalWorkflowPayloads.Action(3L,"Không tiếp tục đề nghị chấm dứt cũ",false,false)));
        assertEquals("PROPOSED",workflow.getStatus()); assertEquals(4,workflow.getRevision());
        verifyNoInteractions(records,payments,notices);
    }

    @ParameterizedTest @CsvSource({"owner,REJECT", "tenant,WITHDRAW"})
    void dismissingTheProposalStillRequiresTheCorrectParty(String actor,String action) {
        assertThrows(AppException.class,()->service.act("rental",actor,action,
            new RentalWorkflowPayloads.Action(4L,"Không tiếp tục đề nghị chấm dứt cũ",false,false)));
        assertEquals("PROPOSED",workflow.getStatus()); assertEquals(4,workflow.getRevision());
        verifyNoInteractions(records,payments,notices);
    }

    @Test void expiredLeaseCannotBeAgreedEvenWithAcknowledgement() {
        assertThrows(AppException.class,()->service.act("rental","tenant","ACCEPT",
            new RentalWorkflowPayloads.Action(4L,"Đồng ý ngày kết thúc trong đề nghị",true,false)));
        assertNull(contract.getAgreedEarlyEndExclusive());
        assertEquals("PROPOSED",workflow.getStatus());
        verifyNoInteractions(records,payments,notices);
    }
}
