package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
class RentalDefaultTest {
    @Mock RentalRequestRepository rentals; @Mock RentalStartChangeRepository changes; @Mock RentalOnboardingEventRepository events;
    @Mock PaymentRequestRepository payments; @Mock PaymentRequestService paymentService; @Mock ContractRepository contracts;
    @Mock ContractRevisionRepository revisions; @Mock DepositRecordRepository deposits; @Mock SignatureRequestRepository signatures;
    @Mock ContractDocumentRepository documents; @Mock ParkingReservationService parking; @Mock ListingStatusService listingStatus;
    @Mock RentalCostCalculator calculator; @Mock ListingRepository listings; @Mock PropertyBranchRepository branches;
    @Mock BillingTime time; @Spy ObjectMapper mapper=new ObjectMapper(); @Mock ContractDataBuilder builder;
    @Mock PlatformTransactionManager transactions; @InjectMocks RentalOnboardingService service;
    Instant now;RentalRequest rental;Contract contract;PaymentRequest rent;DepositRecord deposit;ContractRevision revision;
    @BeforeEach void setup() {
        now=Instant.parse("2026-11-02T03:00:00Z");
        when(time.now()).thenAnswer(i->now);when(time.today()).thenAnswer(i->now.atZone(BillingTime.ZONE).toLocalDate());
        rental=RentalRequest.builder().id("r").ownerId("o").renterId("t").status(RentalRequestStatus.ACCEPTED)
            .reservationPolicyVersion("RESERVATION_DEFAULT_V1").reservationPolicyAcceptedAt(now.minusSeconds(200000))
            .moveInDate(LocalDate.of(2026,11,1)).leaseMonths(3).listing(Listing.builder().id("l").status(ListingStatus.RESERVED).build()).build();
        contract=Contract.builder().id("c").currentRevisionId("rev").status(ContractStatus.DRAFT).build();
        revision=ContractRevision.builder().id("rev").initialPaymentSnapshot("{}").build();
        rent=PaymentRequest.builder().id("rent").rentalRequestId("r").type(PaymentType.INITIAL).status(PaymentStatus.AWAITING_TRANSFER)
            .totalAmount(new BigDecimal("5000000")).dueAt(now.minusSeconds(1)).build();
        var dp=PaymentRequest.builder().id("dp").type(PaymentType.RESERVATION_DEPOSIT).status(PaymentStatus.CONFIRMED).build();
        deposit=DepositRecord.builder().initialPaymentRequestId("dp").heldAmount(new BigDecimal("10000000"))
            .originalAmount(new BigDecimal("10000000")).status(DepositStatus.HELD).build();
        when(rentals.findByIdForUpdate("r")).thenReturn(Optional.of(rental));
        when(contracts.findByRentalRequestId("r")).thenReturn(Optional.of(contract));when(contracts.findByIdForUpdate("c")).thenReturn(Optional.of(contract));
        when(revisions.findById("rev")).thenReturn(Optional.of(revision));when(builder.onboardingPayment(any(),any(),anyMap(),anyList())).thenReturn(Map.of());
        when(payments.findByRentalRequestIdAndType("r",PaymentType.INITIAL)).thenReturn(Optional.of(rent));
        when(payments.findByRentalRequestIdAndTypeForUpdate("r",PaymentType.INITIAL)).thenReturn(Optional.of(rent));
        when(payments.findByRentalRequestIdAndType("r",PaymentType.RESERVATION_DEPOSIT)).thenReturn(Optional.of(dp));
        when(payments.findByRentalRequestIdAndTypeForUpdate("r",PaymentType.RESERVATION_DEPOSIT)).thenReturn(Optional.of(dp));
        when(deposits.findByRentalRequestId("r")).thenReturn(Optional.of(deposit));when(deposits.findByRentalRequestIdForUpdate("r")).thenReturn(Optional.of(deposit));
        readyDocument();
    }
    private void readyDocument() {when(documents.findByContractIdAndRevisionId("c","rev")).thenReturn(List.of(ContractDocument.builder()
        .documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.PREVIEW).storageObjectId("file").status(DocumentGenerationStatus.READY)
        .renderFingerprint(ContractPreviewIdentity.fingerprint(revision,"INTERNAL")).build()));}
    private void notice() {service.notifyDefault("r","o",new RentalOnboardingService.Cancellation("Thông báo nghĩa vụ đã quá hạn"));}
    private void elapsed() {now=rental.getDefaultRespondBy();}
    private RentalOnboardingService.DefaultCompletion decision(){return new RentalOnboardingService.DefaultCompletion("Khách không phản hồi đúng hạn",true,true);}
    @Test void noticeIsNotForfeitureAndGraceReallyAllowsRentPayment() {
        notice();assertEquals(RentalRequestStatus.ACCEPTED,rental.getStatus());assertEquals(DepositStatus.HELD,deposit.getStatus());
        assertEquals(rental.getDefaultRespondBy(),rent.getDueAt());assertEquals(now.plusSeconds(86400),rent.getDueAt());
        assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));verifyNoInteractions(parking);
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"EXPIRED","OVERDUE"})
    void finalNoticeReopensUnpaidObligationInsteadOfLeavingAnUnpayableExpiredQr(PaymentStatus status) {
        rent.setStatus(status);notice();assertEquals(PaymentStatus.AWAITING_TRANSFER,rent.getStatus());assertEquals(now.plusSeconds(86400),rent.getDueAt());
    }
    @Test void completionAtBoundaryRetainsOnlyActualDepositAndIsIdempotent() {
        notice();elapsed();service.completeDefault("r","o",decision());service.completeDefault("r","o",decision());
        assertEquals(ContractStatus.CANCELLED,contract.getStatus());assertEquals(PaymentStatus.CANCELLED,rent.getStatus());
        assertEquals(new BigDecimal("10000000"),deposit.getRetainedAmount());assertEquals(BigDecimal.ZERO,deposit.getHeldAmount());
        verify(parking,times(1)).releaseReservationsForRequest("r");verify(listingStatus,times(1)).releaseReserved(any(),eq("o"),anyString());
        verify(paymentService,never()).createRentRefundObligation(any(),any());
    }
    @Test void noConsentOrUnreadyDocumentNeverAllowsRetention() {
        rental.setReservationPolicyVersion(null);assertThrows(AppException.class,this::notice);
        rental.setReservationPolicyVersion("RESERVATION_DEFAULT_V1");when(documents.findByContractIdAndRevisionId("c","rev")).thenReturn(List.of());
        assertThrows(AppException.class,this::notice);
    }
    @Test void staleRenderOrProviderSignatureInAnExpiredRequestNeedsReconciliation() {
        revision.setFinancialSnapshot("{\"changed\":true}");assertThrows(AppException.class,this::notice);
        readyDocument();when(signatures.findByContractIdOrderByCreatedAtDesc("c")).thenReturn(List.of(SignatureRequest.builder()
            .status(SignatureRequestStatus.EXPIRED).providerSignatureValue("provider-signed-not-yet-embedded").build()));
        assertThrows(AppException.class,this::notice);
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"TRANSFER_REPORTED","DISPUTED","REJECTED"})
    void transferOrDisputeInvalidatesNotice(PaymentStatus status) {notice();elapsed();rent.setStatus(status);assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));}
    @Test void tenantResponseStopsRetentionEvenAfterDeadline() {notice();elapsed();service.disputeDefault("r","t",new RentalOnboardingService.Cancellation("Tôi cần đối soát khoản chuyển"));assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));}
    @Test void reopenedDeadlineOrRevisionInvalidatesOldNotice() {
        notice();elapsed();rent.setDueAt(now.plusSeconds(86400));assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));
        rent.setDueAt(now);contract.setCurrentRevisionId("new");assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));
    }
    @Test void changingTermsEvenUnderTheSameRevisionIdNeedsANewNotice() {
        notice();elapsed();revision.setFinancialSnapshot("{\"newRent\":6000000}");readyDocument();
        assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));
    }
    @ParameterizedTest @EnumSource(value=SignatureRequestStatus.class,names={"CREATED","PENDING_USER_CONFIRMATION","PROVIDER_SIGNED","EMBEDDING","FAILED"})
    void unknownOrInFlightSignatureBlocksRetention(SignatureRequestStatus status) {
        notice();elapsed();when(signatures.findByContractIdOrderByCreatedAtDesc("c")).thenReturn(List.of(SignatureRequest.builder().status(status).failureCode("PROVIDER_STATE_UNKNOWN").build()));
        assertThrows(AppException.class,()->service.completeDefault("r","o",decision()));
    }
    private void tenantSigning() {
        contract.setStatus(ContractStatus.TENANT_SIGNATURE_PENDING);contract.setTenantSigningDueAt(now.minusSeconds(1));
        rent.setStatus(PaymentStatus.CONFIRMED);rent.setConfirmedAt(now.minusSeconds(200000));
        when(signatures.findByContractIdOrderByCreatedAtDesc("c")).thenReturn(List.of(SignatureRequest.builder().status(SignatureRequestStatus.SIGNED).signerRole(SignerRole.LANDLORD).revisionId("rev").build()));
    }
    @Test void tenantSigningHasOwnWindowAndGraceThenRefundsRentNotDeposit() {
        tenantSigning();notice();assertTrue(service.refresh("r","t").canSign());elapsed();service.completeDefault("r","o",decision());
        verify(paymentService).createRentRefundObligation(rent,new BigDecimal("5000000"));assertEquals(PaymentStatus.CONFIRMED,rent.getStatus());
        assertEquals(DepositStatus.RETAINED_BY_LANDLORD,deposit.getStatus());
    }
    @Test void combinedReceiptRefundExcludesDeposit() {
        tenantSigning();deposit.setInitialPaymentRequestId("rent");rent.setTotalAmount(new BigDecimal("15000000"));notice();elapsed();
        service.completeDefault("r","o",decision());verify(paymentService).createRentRefundObligation(rent,new BigDecimal("5000000"));
    }
    @Test void ownerCannotBlameTenantBeforeSupplyingOwnSignature() {
        rent.setStatus(PaymentStatus.CONFIRMED);rent.setConfirmedAt(now.minusSeconds(300000));assertThrows(AppException.class,this::notice);
    }
    @Test void zeroDepositNoForfeitureAndTenantCannotComplete() {
        deposit.setHeldAmount(BigDecimal.ZERO);assertThrows(AppException.class,this::notice);
        assertThrows(AppException.class,()->service.completeDefault("r","t",decision()));
    }
    @Test void missingAttestationBlocksRelease() {
        notice();elapsed();assertThrows(AppException.class,()->service.completeDefault("r","o",new RentalOnboardingService.DefaultCompletion("Chưa xác nhận việc thông báo",false,true)));
        verifyNoInteractions(parking);
    }
    @Test void bothPartiesCanCancelAfterOwnerSignatureButNotAnUncertainTenantSignature() {
        tenantSigning();assertTrue(service.refresh("r","t").canRequestCancellation());
        service.requestCancellation("r","t",new RentalOnboardingService.Cancellation("Hai bên thỏa thuận hủy và hoàn tiền"));
        assertFalse(service.refresh("r","t").canSign());
        service.decideCancellation("r","o",new RentalOnboardingService.Decision(true,"Thống nhất hoàn lại các khoản"));
        verify(paymentService).createRefundObligation(rent);verify(paymentService,times(2)).createRefundObligation(any());assertEquals(ContractStatus.CANCELLED,contract.getStatus());
    }
    @Test void uncertainSignaturePreventsMutualCancellationToo() {
        tenantSigning();when(signatures.findByContractIdOrderByCreatedAtDesc("c")).thenReturn(List.of(SignatureRequest.builder().status(SignatureRequestStatus.EMBEDDING).signerRole(SignerRole.TENANT).build()));
        assertFalse(service.refresh("r","t").canRequestCancellation());
        assertThrows(AppException.class,()->service.requestCancellation("r","t",new RentalOnboardingService.Cancellation("Hai bên muốn đối soát trước khi hủy")));
    }
    private void unpaidHold() {
        var dp=payments.findByRentalRequestIdAndType("r",PaymentType.RESERVATION_DEPOSIT).orElseThrow();
        dp.setStatus(PaymentStatus.AWAITING_TRANSFER);dp.setTotalAmount(new BigDecimal("10000000"));
        deposit.setHeldAmount(BigDecimal.ZERO);deposit.setStatus(DepositStatus.PENDING);rent.setDueAt(now.plusSeconds(3600));
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"o","t"})
    void eitherPartyCanCancelUnpaidHoldImmediatelyAndRetriesDoNotReleaseAnotherHold(String actor) {
        unpaidHold();service.cancelUnpaid("r",actor);
        assertEquals(actor.equals("o")?RentalRequestStatus.CANCELLED_BY_OWNER:RentalRequestStatus.CANCELLED_BY_RENTER,rental.getStatus());
        assertEquals(PaymentStatus.CANCELLED,rent.getStatus());assertEquals(ContractStatus.CANCELLED,contract.getStatus());
        assertNull(rental.getHoldExpiresAt());service.cancelUnpaid("r","o");service.cancelUnpaid("r","t");
        verify(parking,times(1)).releaseReservationsForRequest("r");verify(listingStatus,times(1)).releaseReserved(any(),eq(actor),anyString());
        verify(paymentService,never()).createRefundObligation(any());
    }
    @Test void cancellingPendingRequestDoesNotReleaseSomeoneElsesReservedListing() {
        unpaidHold();rental.setStatus(RentalRequestStatus.PENDING);service.cancelUnpaid("r","o");verifyNoInteractions(listingStatus);
    }
    @ParameterizedTest @EnumSource(value=PaymentStatus.class,names={"TRANSFER_REPORTED","DISPUTED","REJECTED","CONFIRMED"})
    void reportedOrPaidFundsBlockImmediateCancel(PaymentStatus status) {
        unpaidHold();rent.setStatus(status);assertThrows(AppException.class,()->service.cancelUnpaid("r","o"));
        assertEquals(RentalRequestStatus.ACCEPTED,rental.getStatus());verifyNoInteractions(parking,listingStatus);
    }
    @Test void paidDepositCannotHideBehindUnpaidFirstRentOrReopenedProof() {
        assertThrows(AppException.class,()->service.cancelUnpaid("r","t"));
        unpaidHold();rent.setPayerReportedAt(now.minusSeconds(10));assertThrows(AppException.class,()->service.cancelUnpaid("r","t"));
        verifyNoInteractions(parking,listingStatus);
    }
    @Test void zeroDepositWaiverMayCancelButPositiveHeldBalanceAndSignaturesMayNot() {
        unpaidHold();var dp=payments.findByRentalRequestIdAndType("r",PaymentType.RESERVATION_DEPOSIT).orElseThrow();
        dp.setStatus(PaymentStatus.CONFIRMED);dp.setTotalAmount(BigDecimal.ZERO);
        service.cancelUnpaid("r","t");assertEquals(PaymentStatus.CONFIRMED,dp.getStatus());
    }
    @Test void unknownSignatureAndOtherUsersCannotCancelUnpaidHold() {
        unpaidHold();assertThrows(AppException.class,()->service.cancelUnpaid("r","stranger"));
        when(signatures.findByContractIdOrderByCreatedAtDesc("c")).thenReturn(List.of(SignatureRequest.builder().status(SignatureRequestStatus.CREATED).build()));
        assertThrows(AppException.class,()->service.cancelUnpaid("r","o"));verifyNoInteractions(parking,listingStatus);
    }
}
