package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness=Strictness.LENIENT)
class RentalOnboardingServiceTest {
    @Mock RentalRequestRepository rentals; @Mock RentalStartChangeRepository changes;
    @Mock RentalOnboardingEventRepository events; @Mock PaymentRequestRepository payments;
    @Mock PaymentRequestService paymentService; @Mock ContractRepository contracts;
    @Mock ContractRevisionRepository revisions; @Mock ParkingReservationService parking;
    @Mock com.hs.payment.repository.DepositRecordRepository deposits;
    @Mock SignatureRequestRepository signatures; @Mock ContractDocumentRepository documents;
    @Mock ListingStatusService listingStatus; @Mock RentalCostCalculator calculator;
    @Mock ListingRepository listings; @Mock PropertyBranchRepository branches;
    @Mock BillingTime time; @Spy ObjectMapper mapper=new ObjectMapper();
    @Mock ContractDataBuilder builder; @Mock PlatformTransactionManager transactions;
    @Mock ContractTemplateVersionRepository draftTemplates;
    @InjectMocks RentalOnboardingService service;
    Instant now; RentalRequest rental; PaymentRequest deposit;
    @BeforeEach void setup() {
        now=Instant.parse("2026-10-21T03:00:00Z");
        when(time.now()).thenAnswer(i->now);when(time.today()).thenAnswer(i->now.atZone(BillingTime.ZONE).toLocalDate());
        rental=RentalRequest.builder().id("rental").ownerId("owner").renterId("tenant").status(RentalRequestStatus.ACCEPTED)
            .moveInDate(LocalDate.of(2026,11,1)).leaseMonths(3).monthlyRentPrice(new BigDecimal("5000000"))
            .depositAmount(new BigDecimal("10000000")).listing(Listing.builder().id("listing").status(ListingStatus.RESERVED).build()).build();
        deposit=PaymentRequest.builder().id("deposit").rentalRequestId("rental").type(PaymentType.RESERVATION_DEPOSIT)
            .status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("10000000")).confirmedAt(now).build();
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(rental));
        when(payments.findByRentalRequestIdAndType("rental",PaymentType.RESERVATION_DEPOSIT)).thenReturn(Optional.of(deposit));
        when(changes.save(any())).thenAnswer(i->i.getArgument(0));
        when(listings.findByIdForUpdate("listing")).thenReturn(Optional.of(rental.getListing()));
    }
    private PaymentRequest rent(PaymentStatus status) {
        var p=PaymentRequest.builder().id("rent").rentalRequestId("rental").type(PaymentType.INITIAL).status(status)
            .totalAmount(new BigDecimal("5000000")).confirmedAt(status==PaymentStatus.CONFIRMED?now:null).build();
        when(payments.findByRentalRequestIdAndType("rental",PaymentType.INITIAL)).thenReturn(Optional.of(p));return p;
    }
    @Test void paidWithoutPublishedTemplateExplainsTheBlockInsteadOfPretendingToGenerate() {
        rental.getListing().setCategory(ListingCategory.HOUSE);
        rent(PaymentStatus.CONFIRMED);
        var view=service.refresh("rental","owner");
        assertEquals("CONTRACT_TEMPLATE_REQUIRED",view.stage());
        assertTrue(view.message().contains("chưa có mẫu"));assertTrue(view.message().contains("quản trị viên"));
        assertFalse(view.message().contains("đang tự tạo"));
        when(draftTemplates.findCurrentPublishedSystemVersions(ListingCategory.HOUSE)).thenReturn(List.of(
            com.hs.contract.model.ContractTemplateVersion.builder().id("published").build()));
        assertEquals("PREPARING_CONTRACT",service.refresh("rental","tenant").stage());
        when(draftTemplates.countActiveSystemTemplates(ListingCategory.HOUSE)).thenReturn(2L);
        var duplicate=service.refresh("rental","owner");assertEquals("CONTRACT_TEMPLATE_REQUIRED",duplicate.stage());
        assertTrue(duplicate.message().contains("bị trùng"));
    }
    @Test void positiveDepositRequiresItsOwnSignatureBeforeLeaseSigning() {
        var agreementService=mock(com.hs.contract.deposit.DepositAgreementService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"depositAgreements",agreementService);
        when(agreementService.find("rental")).thenReturn(Optional.of(Contract.builder().id("deposit-doc").agreementKind("DEPOSIT").status(ContractStatus.DRAFT).build()));
        when(contracts.findByRentalRequestId("rental")).thenReturn(Optional.of(Contract.builder().id("lease").status(ContractStatus.TENANT_SIGNATURE_PENDING).build()));
        rental.setMoveInDate(LocalDate.of(2026,10,21));rent(PaymentStatus.CONFIRMED);
        var blocked=service.refresh("rental","tenant");assertEquals("AWAITING_DEPOSIT_SIGNATURE",blocked.stage());
        assertFalse(blocked.canSign());assertNull(blocked.signingDueAt());assertEquals("deposit-doc",blocked.depositAgreementContractId());
        assertNull(blocked.defaultCase().eligibleKind());
        when(agreementService.signedOrExempt(rental)).thenReturn(true);
        assertTrue(service.refresh("rental","tenant").canSign());
    }
    private RentalStartChange pending() {
        var c=RentalStartChange.builder().id("change").rentalRequestId("rental").previousDate(rental.getMoveInDate())
            .requestedDate(LocalDate.of(2026,10,25)).status("REQUESTED").respondBy(now.plusSeconds(86400)).build();
        when(changes.findById("change")).thenReturn(Optional.of(c));
        when(changes.findByRentalRequestIdOrderByRequestedAtDesc("rental")).thenReturn(List.of(c));return c;
    }
    @Test void depositPaidBeforeMoveInNeverOpensRentOrSigning() {
        var v=service.refresh("rental","tenant");assertEquals("WAITING_START",v.stage());assertFalse(v.canPrepareContract());assertFalse(v.canSign());
        verify(paymentService,never()).createFirstRent(any(),any(),any());
    }
    @Test void openingBoundaryUsesVietnamMidnightAndOriginalDeadline() {
        now=Instant.parse("2026-10-31T17:00:00Z");
        var p=PaymentRequest.builder().id("rent").status(PaymentStatus.AWAITING_TRANSFER).build();
        when(paymentService.createFirstRent(eq(rental),any(),any())).thenReturn(p);
        service.refresh("rental","tenant");
        verify(paymentService).createFirstRent(rental,Instant.parse("2026-10-31T17:00:00Z"),Instant.parse("2026-11-01T17:00:00Z"));
    }
    @Test void strangerCannotInspectOrMutate() {assertThrows(AppException.class,()->service.refresh("rental","stranger"));verifyNoInteractions(paymentService);}
    @Test void tenantCanRequestEarlierStartButOwnerCannotImpersonateTenant() {
        service.requestEarlier("rental","tenant",new RentalOnboardingService.StartRequest(LocalDate.of(2026,10,25),"Nhận nhà sớm theo lịch mới"));
        verify(changes).save(argThat(c->c.getPreviousDate().equals(LocalDate.of(2026,11,1))&&c.getRequestedDate().equals(LocalDate.of(2026,10,25))));
        assertThrows(AppException.class,()->service.requestEarlier("rental","owner",new RentalOnboardingService.StartRequest(LocalDate.of(2026,10,25),"không đúng vai trò")));
    }
    @Test void approvalMovesBothLeaseDatesAndRechecksParking() {
        var c=pending();var v=service.decideEarlier("rental","change","owner",new RentalOnboardingService.Decision(false,null));
        assertEquals("REJECTED",c.getStatus());assertEquals(LocalDate.of(2026,11,1),v.startsOn());verifyNoInteractions(parking);
        c.setStatus("REQUESTED");
        v=service.decideEarlier("rental","change","owner",new RentalOnboardingService.Decision(true,null));
        assertEquals(LocalDate.of(2026,10,25),v.startsOn());assertEquals(LocalDate.of(2027,1,25),v.endExclusive());
        var order=inOrder(parking,calculator);order.verify(parking).releaseReservationsForRequest("rental");
        order.verify(calculator).calculate(any(),eq(LocalDate.of(2026,10,25)),eq(3),anyInt(),anyInt(),anyInt(),any());
        order.verify(parking).createHeldReservations(rental,rental.getListing(),null);
    }
    @Test void staleOrPastProposalCannotBeApproved() {
        var c=pending();c.setPreviousDate(LocalDate.of(2026,11,2));
        assertThrows(AppException.class,()->service.decideEarlier("rental","change","owner",new RentalOnboardingService.Decision(true,null)));
        c.setPreviousDate(rental.getMoveInDate());c.setRequestedDate(LocalDate.of(2026,10,20));
        assertThrows(AppException.class,()->service.decideEarlier("rental","change","owner",new RentalOnboardingService.Decision(true,null)));
    }
    @Test void cancellationFreezesApprovalOfEarlierStart() {
        pending();rental.setCancellationRequestedAt(now);
        assertThrows(AppException.class,()->service.decideEarlier("rental","change","owner",new RentalOnboardingService.Decision(true,null)));
        assertEquals(LocalDate.of(2026,11,1),rental.getMoveInDate());verifyNoInteractions(parking);
    }
    @Test void outstandingDisputeNeverLooksPaid() {deposit.setStatus(PaymentStatus.DISPUTED);var v=service.refresh("rental","tenant");assertEquals("REQUIRES_REVIEW",v.stage());assertFalse(v.canSign());}
    @Test void transferReportedIsNotAnotherRequestToPay() {deposit.setStatus(PaymentStatus.TRANSFER_REPORTED);assertEquals("AWAITING_DEPOSIT_CONFIRMATION",service.refresh("rental","tenant").stage());}
    @Test void signingRequiresBothPaymentsAndStartDate() {
        when(contracts.findByRentalRequestId("rental")).thenReturn(Optional.of(Contract.builder().id("contract").status(ContractStatus.TENANT_SIGNATURE_PENDING).build()));
        rent(PaymentStatus.CONFIRMED);assertFalse(service.refresh("rental","tenant").canSign());
        rental.setMoveInDate(LocalDate.of(2026,10,21));assertTrue(service.refresh("rental","tenant").canSign());
        deposit.setStatus(PaymentStatus.TRANSFER_REPORTED);assertFalse(service.refresh("rental","tenant").canSign());
    }
    @Test void lateSigningRequiresExplicitReopening() {
        var p=rent(PaymentStatus.CONFIRMED);rental.setMoveInDate(LocalDate.of(2026,10,21));p.setContractDueAt(now.minusSeconds(1));
        assertFalse(service.refresh("rental","tenant").canSign());
        when(payments.findByRentalRequestIdAndTypeForUpdate("rental",PaymentType.INITIAL)).thenReturn(Optional.of(p));
        service.reopenSigning("rental","owner",new RentalOnboardingService.Cancellation("Đã đối soát đầy đủ hai bên"));assertTrue(p.getContractDueAt().isAfter(now));
    }
    @Test void signedOrSigningContractCannotChangeLeaseStart() {
        pending();when(contracts.findByRentalRequestId("rental")).thenReturn(Optional.of(Contract.builder().id("contract").status(ContractStatus.TENANT_SIGNATURE_PENDING).build()));
        var existing=contracts.findByRentalRequestId("rental");
        when(contracts.findByIdForUpdate("contract")).thenReturn(existing);
        assertThrows(AppException.class,()->service.decideEarlier("rental","change","owner",new RentalOnboardingService.Decision(true,null)));verifyNoInteractions(parking);
    }
    @Test void cancellationNeedsAgreementAndCreatesRefundRatherThanAutoTransfer() {
        service.requestCancellation("rental","tenant",new RentalOnboardingService.Cancellation("Hai bên thống nhất hủy thuê"));assertEquals(RentalRequestStatus.ACCEPTED,rental.getStatus());
        when(payments.findByRentalRequestIdAndTypeForUpdate("rental",PaymentType.RESERVATION_DEPOSIT)).thenReturn(Optional.of(deposit));
        service.decideCancellation("rental","owner",new RentalOnboardingService.Decision(true,null));
        verify(paymentService).createRefundObligation(deposit);assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER,rental.getStatus());verify(parking).releaseReservationsForRequest("rental");
    }
}
