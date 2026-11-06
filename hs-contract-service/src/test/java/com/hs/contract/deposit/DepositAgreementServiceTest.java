package com.hs.contract.deposit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.payment.ReservationPolicy;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DepositAgreementServiceTest {
    @Mock DepositAgreementRepository agreements;
    @Mock ContractRepository contracts;
    @Mock SignatureRequestRepository signatures;
    @Mock ContractRevisionRepository revisions;
    @Mock ContractTemplateVersionRepository templates;
    @Mock RentalRequestRepository rentals;
    @Mock com.hs.listing.repository.RentalOnboardingEventRepository events;
    @Mock DepositRecordRepository ledgers;
    @Mock PaymentRequestRepository payments;
    @Mock PaymentLineItemRepository lines;
    @Mock ContractDataBuilder builder;
    @Spy ContractFieldCatalog catalog = new ContractFieldCatalog();
    @Spy ObjectMapper mapper = new ObjectMapper();
    @Mock BillingTime time;
    @InjectMocks DepositAgreementService service;
    final Instant now = Instant.parse("2026-10-24T03:00:00Z");
    RentalRequest rental;
    PaymentRequest payment;

    private Contract depositDocument(ContractStatus status) {
        var c=Contract.builder().id("dc").rentalRequestId("rental").agreementKind("DEPOSIT").status(status).build();
        var d=new DepositAgreement();d.setRentalRequestId("rental");d.setSigningContractId("dc");
        when(agreements.findByRentalRequestId("rental")).thenReturn(Optional.of(d));
        when(contracts.findById("dc")).thenReturn(Optional.of(c));when(contracts.findByIdForUpdate("dc")).thenReturn(Optional.of(c));return c;
    }
    @Test void verifiedTenantSignatureBlocksRefundBeforeFinalDocumentStatus() {
        var c=depositDocument(ContractStatus.TENANT_SIGNATURE_PENDING);
        assertFalse(service.tenantHasSigned("rental"));
        when(signatures.findByContractIdOrderByCreatedAtDesc(c.getId())).thenReturn(List.of(SignatureRequest.builder()
                .signerRole(SignerRole.LANDLORD).status(SignatureRequestStatus.SIGNED).build()));
        assertFalse(service.tenantHasSigned("rental"));
        when(signatures.findByContractIdOrderByCreatedAtDesc(c.getId())).thenReturn(List.of(SignatureRequest.builder()
                .signerRole(SignerRole.TENANT).status(SignatureRequestStatus.SIGNED).build()));
        assertTrue(service.tenantHasSigned("rental"));
    }
    @Test void tenantDefaultEndsTheDepositButPreservesSignatureAndFrozenSnapshot() {
        var c=depositDocument(ContractStatus.ACTIVE);c.setSignedAt(now.minusSeconds(100));var signedAt=c.getSignedAt();
        service.terminateForTenantDefault("rental","Lỗi Bên B: không thanh toán tháng đầu đúng hạn");
        service.terminateForTenantDefault("rental","Lỗi Bên B: không thanh toán tháng đầu đúng hạn");
        assertEquals(ContractStatus.TERMINATED,c.getStatus());assertEquals(now,c.getTerminationCompletedAt());assertEquals(signedAt,c.getSignedAt());
        verify(contracts,times(1)).save(c);verify(agreements,never()).save(any());
        verify(events).save(argThat(e->"DEPOSIT_TERMINATED_TENANT_DEFAULT".equals(e.getAction())&&e.getNote().contains("Lỗi Bên B")&&"SYSTEM".equals(e.getActorId())));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ContractStatus.class,names={"DRAFT","LANDLORD_SIGNATURE_PENDING","TENANT_SIGNATURE_PENDING","CANCELLED"})
    void unsignedDepositCannotBeTerminatedAsATenantDefault(ContractStatus status) {
        depositDocument(status);assertThrows(AppException.class,()->service.terminateForTenantDefault("rental","Quá hạn"));
        verify(contracts,never()).save(any());
    }

    @BeforeEach void setup() throws Exception {
        when(time.now()).thenReturn(now);
        when(time.today()).thenReturn(LocalDate.of(2026,10,24));
        var listing = Listing.builder().id("listing").category(ListingCategory.HOUSE).status(ListingStatus.RESERVED).build();
        rental = RentalRequest.builder().id("rental").listing(listing).ownerId("owner").renterId("tenant")
            .listingSnapshot("{\"listingId\":\"listing\"}").status(RentalRequestStatus.ACCEPTED).depositAmount(new BigDecimal("4000000"))
            .moveInDate(LocalDate.of(2026,11,1)).leaseMonths(6).acceptedAt(now.minusSeconds(600))
            .reservationPolicyVersion(ReservationPolicy.VERSION).reservationPolicyText(ReservationPolicy.TEXT)
            .reservationPolicyHash(ReservationPolicy.hash(ReservationPolicy.TEXT)).reservationPolicyAcceptedAt(now.minusSeconds(700)).build();
        when(rentals.findByIdForUpdate("rental")).thenReturn(Optional.of(rental));
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        payment = PaymentRequest.builder().id("payment").rentalRequestId("rental").listingId("listing")
            .payerId("tenant").payeeId("owner").direction(PaymentDirection.TENANT_TO_LANDLORD).currency("VND")
            .type(PaymentType.RESERVATION_DEPOSIT).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("4000000"))
            .payerReportedAt(now.minusSeconds(60)).payeeConfirmedAt(now).confirmedAt(now).transferReference("HS-DEPOSIT").build();
        when(payments.findById("payment")).thenReturn(Optional.of(payment));
        when(ledgers.findByRentalRequestId("rental")).thenReturn(Optional.of(DepositRecord.builder()
            .rentalRequestId("rental").initialPaymentRequestId("payment").originalAmount(new BigDecimal("4000000"))
            .heldAmount(new BigDecimal("4000000")).status(DepositStatus.HELD).build()));
        when(lines.findByPaymentRequestIdOrderBySortOrderAsc("payment")).thenReturn(List.of(PaymentLineItem.builder()
            .paymentRequestId("payment").type("DEPOSIT").amount(new BigDecimal("4000000")).build()));
        var template = ContractTemplate.builder().id("template").documentKind("DEPOSIT").category(ListingCategory.HOUSE).build();
        when(templates.findPublishedDepositVersions(ListingCategory.HOUSE)).thenReturn(List.of(ContractTemplateVersion.builder().id("version").template(template).build()));
        when(contracts.save(any())).thenAnswer(i -> {Contract c=i.getArgument(0);if(c.getId()==null)c.setId("envelope");return c;});
        when(revisions.save(any())).thenAnswer(i -> {ContractRevision r=i.getArgument(0);if(r.getId()==null)r.setId("revision");return r;});
        var model=DepositDataModel.dummy(new ContractRenderService(mapper).buildDummyDataModel());
        when(builder.build(rental,listing,payment)).thenReturn(ContractDataBuilder.ContractSnapshots.builder()
            .landlord(group(model,"landlord")).tenant(group(model,"tenant")).property(group(model,"property"))
            .lease(group(model,"lease")).financial(group(model,"rent")).build());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> group(Map<String,Object> m,String key) {return (Map<String,Object>)m.get(key);}

    @Test void createsSeparateFrozenDepositEnvelopeWithNoTenancySideEffects() {
        Contract c=service.createAutomatically("rental");
        assertEquals("DEPOSIT",c.getAgreementKind());assertEquals(ContractStatus.DRAFT,c.getStatus());
        assertNull(c.getSignedAt());assertEquals("payment",c.getRentalPaymentId());
        var saved=ArgumentCaptor.forClass(DepositAgreement.class);verify(agreements).save(saved.capture());
        DepositAgreement d=saved.getValue();assertEquals(c.getId(),d.getSigningContractId());
        assertEquals(ReservationPolicy.hash(d.getSnapshotJson()),d.getSnapshotHash());
        assertTrue(d.getSnapshotJson().contains("4.000.000"));assertTrue(d.getSnapshotJson().contains("HS-DEPOSIT"));
        assertEquals(ListingStatus.RESERVED,rental.getListing().getStatus());assertEquals(RentalRequestStatus.ACCEPTED,rental.getStatus());
        verify(rentals,never()).save(any());verify(payments,never()).save(any());
        when(agreements.findBySigningContractId(c.getId())).thenReturn(Optional.of(d));
        rental.setMoveInDate(LocalDate.of(2026,12,1));payment.setTransferReference("CHANGED-LIVE");
        assertEquals("HS-DEPOSIT",group(service.dataModel(c),"depositPayment").get("transferReference"));
        d.setSnapshotJson(d.getSnapshotJson()+" ");assertThrows(AppException.class,()->service.dataModel(c));
    }
    @Test void repeatedReceiptOrRetryReturnsExistingDocumentWithoutRebuildingSnapshot() {
        var c=Contract.builder().id("existing").agreementKind("DEPOSIT").build();var d=new DepositAgreement();d.setSigningContractId(c.getId());
        when(agreements.findByRentalRequestId("rental")).thenReturn(Optional.of(d));when(contracts.findById(c.getId())).thenReturn(Optional.of(c));
        assertSame(c,service.createAutomatically("rental"));verifyNoInteractions(builder);verify(agreements,never()).save(any());
    }
    @Test void missingTemplateNeverChangesConfirmedMoneyOrCreatesFakeDocument() {
        when(templates.findPublishedDepositVersions(ListingCategory.HOUSE)).thenReturn(List.of());
        assertThrows(AppException.class,()->service.createAutomatically("rental"));
        assertEquals(PaymentStatus.CONFIRMED,payment.getStatus());verify(contracts,never()).save(any());verify(payments,never()).save(any());
    }
    @Test void zeroDepositIsExemptAndDoesNotCreateReceipt() {
        rental.setDepositAmount(BigDecimal.ZERO);assertTrue(service.signedOrExempt(rental));assertNull(service.createAutomatically("rental"));
        verifyNoInteractions(ledgers,templates);verify(agreements,never()).save(any());
    }
    @Test void exemptionDoesNotAdvertiseSigningOrAllowDepositSignatureAndReopen() {
        rental.setDepositAmount(new BigDecimal("0.00"));
        when(ledgers.findByRentalRequestId("rental")).thenReturn(Optional.empty());
        var view=service.overview("rental","tenant");
        assertFalse(view.required());assertFalse(view.signingAllowed());assertNull(view.signingContractId());
        assertTrue(view.blockedReason().contains("bỏ qua hợp đồng cọc"));
        var c=Contract.builder().rentalRequestId("rental").agreementKind("DEPOSIT").build();
        assertThrows(AppException.class,()->service.validateSigning(c));
        assertThrows(AppException.class,()->service.reopen("rental","owner"));
        verify(contracts,never()).save(any());verify(agreements,never()).save(any());
    }
    @Test void missingAmountIsNotTreatedAsAZeroDepositExemption() {
        rental.setDepositAmount(null);
        assertFalse(service.signedOrExempt(rental));
        var view=service.overview("rental","tenant");
        assertTrue(view.required());assertFalse(view.signingAllowed());
        assertTrue(view.blockedReason().contains("Chưa xác định"));assertNull(service.createAutomatically("rental"));
        verify(agreements,never()).save(any());
    }
    @Test void changingTheLiveListingDoesNotWaiveTheAcceptedPositiveDeposit() {
        rental.getListing().setDepositType(DepositType.NONE);rental.getListing().setDepositAmount(BigDecimal.ZERO);
        assertFalse(service.signedOrExempt(rental));assertTrue(service.overview("rental","tenant").required());
        assertEquals("DEPOSIT",service.createAutomatically("rental").getAgreementKind());
    }
    @Test void strangerCannotReadAndPolicyTamperingCannotBeRendered() {
        assertThrows(AppException.class,()->service.participant("rental","stranger"));
        rental.setReservationPolicyText("Changed terms");assertThrows(AppException.class,()->service.createAutomatically("rental"));
        verify(agreements,never()).save(any());
    }
    @Test void signedDepositOnlyOpensLeaseSigningWindowNotTheTenancy() {
        var c=Contract.builder().rentalRequestId("rental").agreementKind("DEPOSIT").build();
        var combined=PaymentRequest.builder().status(PaymentStatus.CONFIRMED).build();
        when(payments.findByRentalRequestIdAndType("rental",PaymentType.INITIAL)).thenReturn(Optional.of(combined));
        service.landlordSigned(c);assertEquals(now.plusSeconds(48*3600),c.getTenantSigningDueAt());
        service.bothSigned(c);assertEquals(now.plusSeconds(48*3600),combined.getContractDueAt());
        assertEquals(ListingStatus.RESERVED,rental.getListing().getStatus());assertEquals(RentalRequestStatus.ACCEPTED,rental.getStatus());
        verify(rentals,never()).save(any());
    }
    @Test void depositOverviewIsReadableBeforeTheMoveInDateAndContainsOnlyScalarData() {
        var envelope=Contract.builder().id("deposit-doc").agreementKind("DEPOSIT").status(ContractStatus.DRAFT).build();
        var document=new DepositAgreement();document.setSigningContractId(envelope.getId());
        when(agreements.findByRentalRequestId("rental")).thenReturn(Optional.of(document));
        when(contracts.findById(envelope.getId())).thenReturn(Optional.of(envelope));
        assertTrue(rental.getMoveInDate().isAfter(time.today()));
        var view=service.overview("rental","tenant");
        assertTrue(view.required());assertTrue(view.signingAllowed());assertNull(view.blockedReason());
        assertEquals("deposit-doc",view.signingContractId());
        assertThrows(AppException.class,()->service.overview("rental","stranger"));
        verify(rentals,never()).save(any());verify(payments,never()).save(any());
    }
    @Test void expiredOrCancelledDepositOverviewStillIdentifiesTheDocumentWithoutOpeningSigning() {
        var envelope=Contract.builder().id("deposit-doc").agreementKind("DEPOSIT").status(ContractStatus.TENANT_SIGNATURE_PENDING)
            .tenantSigningDueAt(now.minusSeconds(1)).build();
        var document=new DepositAgreement();document.setSigningContractId(envelope.getId());
        when(agreements.findByRentalRequestId("rental")).thenReturn(Optional.of(document));
        when(contracts.findById(envelope.getId())).thenReturn(Optional.of(envelope));
        var expired=service.overview("rental","owner");
        assertFalse(expired.signingAllowed());assertTrue(expired.blockedReason().contains("Hết hạn"));
        assertEquals("deposit-doc",expired.signingContractId());
        rental.setCancellationRequestedAt(now);
        var cancelled=service.overview("rental","tenant");assertFalse(cancelled.signingAllowed());
        assertEquals("deposit-doc",cancelled.signingContractId());
    }
}
