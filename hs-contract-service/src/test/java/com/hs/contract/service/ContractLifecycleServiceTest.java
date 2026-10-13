package com.hs.contract.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.billing.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.PaymentRequestService;
import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ContractLifecycleServiceTest {
    com.hs.storage.repository.StorageObjectRepository storageObjects=mock(com.hs.storage.repository.StorageObjectRepository.class);
    ContractRepository contracts=mock(ContractRepository.class);
    ContractRevisionRepository revisions=mock(ContractRevisionRepository.class);
    ContractRenewalRepository renewals=mock(ContractRenewalRepository.class);
    ContractClosureRepository closures=mock(ContractClosureRepository.class);
    RentalRequestRepository rentals=mock(RentalRequestRepository.class);
    MonthlyInvoiceRepository invoices=mock(MonthlyInvoiceRepository.class);
    DepositRecordRepository deposits=mock(DepositRecordRepository.class);
    PaymentRequestRepository paymentRepo=mock(PaymentRequestRepository.class);
    PaymentRequestService payments=mock(PaymentRequestService.class);
    SignatureRequestRepository signatures=mock(SignatureRequestRepository.class);
    ContractDocumentRepository documents=mock(ContractDocumentRepository.class);
    ListingStatusService listings=mock(ListingStatusService.class);
    ParkingReservationService parking=mock(ParkingReservationService.class);
    BillingTime time=mock(BillingTime.class);
    ContractLifecycleService service;
    Contract root;
    ContractRevision revision;
    RentalRequest rental;
    DepositRecord deposit;
    Map<String,Contract> contractData=new HashMap<>();
    Map<String,ContractRevision> revisionData=new HashMap<>();
    List<ContractRenewal> requests=new ArrayList<>();
    Map<String,ContractClosure> closureData=new HashMap<>();
    Map<String,PaymentRequest> paymentData=new HashMap<>();
    List<MonthlyInvoice> bills=new ArrayList<>();
    @BeforeEach void setup() {
        service=new ContractLifecycleService(contracts,revisions,renewals,closures,rentals,invoices,deposits,paymentRepo,payments,
                signatures,documents,listings,parking,new ObjectMapper().findAndRegisterModules(),time,mock(PlatformTransactionManager.class),storageObjects);
        root=Contract.builder().id("root").contractNumber("HD-DEMO").rentalRequestId("rental").listingId("listing")
                .landlordId("owner").tenantId("tenant").status(ContractStatus.ACTIVE).currentRevisionId("original")
                .templateId("template").templateVersionId("template-v1").signedAt(Instant.parse("2026-10-12T03:00:00Z"))
                .paymentStatus(ContractPaymentStatus.PAID).build();
        revision=ContractRevision.builder().id("original").leaseSnapshot("{\"durationMonths\":3}").financialSnapshot("{\"amountValue\":5000000}")
                .landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}").templateVersionId("template-v1")
                .chargesSnapshot("[{\"billingMethod\":\"PER_KWH\",\"unitAmount\":3500},{\"billingMethod\":\"PER_M3\",\"unitAmount\":16000}]")
                .policiesSnapshot("{\"depositRefundDays\":15}").specialTerms("Giữ nguyên điều khoản gốc.").build();
        rental=RentalRequest.builder().id("rental").moveInDate(LocalDate.of(2026,10,12)).leaseMonths(3).build();
        deposit=DepositRecord.builder().originalAmount(new BigDecimal("20000000")).heldAmount(new BigDecimal("20000000"))
                .refundableAmount(new BigDecimal("20000000")).status(DepositStatus.HELD).build();
        contractData.put("root",root); revisionData.put("original",revision);
        when(contracts.findById(anyString())).thenAnswer(x->Optional.ofNullable(contractData.get(x.getArgument(0))));
        when(contracts.findByIdForUpdate(anyString())).thenAnswer(x->Optional.ofNullable(contractData.get(x.getArgument(0))));
        when(contracts.save(any())).thenAnswer(x->{ Contract c=x.getArgument(0); if(c.getId()==null)c.setId(UUID.randomUUID().toString());contractData.put(c.getId(),c);return c; });
        when(revisions.findById(anyString())).thenAnswer(x->Optional.ofNullable(revisionData.get(x.getArgument(0))));
        when(revisions.save(any())).thenAnswer(x->{ ContractRevision r=x.getArgument(0);r.setId(UUID.randomUUID().toString());revisionData.put(r.getId(),r);return r; });
        when(renewals.findByContractIdOrderByRequestedAtDesc("root")).thenReturn(requests);
        when(renewals.save(any())).thenAnswer(x->{ContractRenewal r=x.getArgument(0);if(r.getId()==null){r.setId(UUID.randomUUID().toString());requests.add(r);}return r;});
        when(renewals.findById(anyString())).thenAnswer(x->requests.stream().filter(r->r.getId().equals(x.getArgument(0))).findFirst());
        when(renewals.findByAgreementContractId(anyString())).thenAnswer(x->requests.stream().filter(r->Objects.equals(r.getAgreementContractId(),x.getArgument(0))).findFirst());
        when(closures.findById(anyString())).thenAnswer(x->Optional.ofNullable(closureData.get(x.getArgument(0))));
        when(closures.save(any())).thenAnswer(x->{ContractClosure c=x.getArgument(0);closureData.put(c.getContractId(),c);return c;});
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        when(deposits.findByRentalRequestIdForUpdate("rental")).thenAnswer(x->Optional.of(deposit));
        when(deposits.findByRentalRequestId("rental")).thenAnswer(x->Optional.of(deposit));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("root")).thenReturn(bills);
        when(invoices.findByContractIdAndPeriodIndex(eq("root"),anyInt())).thenAnswer(x->bills.stream().filter(b->b.getPeriodIndex()==(Integer)x.getArgument(1)).findFirst());
        when(invoices.saveAndFlush(any())).thenAnswer(x->{MonthlyInvoice b=x.getArgument(0); if(b.getId()==null)b.setId(UUID.randomUUID().toString());bills.add(b);return b;});
        when(paymentRepo.findById(anyString())).thenAnswer(x->Optional.ofNullable(paymentData.get(x.getArgument(0))));
        when(payments.createContractSettlement(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyBoolean(),anyString())).thenAnswer(x->{
            PaymentRequest p=PaymentRequest.builder().id("settlement").payerId(x.getArgument(3)).payeeId(x.getArgument(4)).totalAmount(x.getArgument(5))
                    .dueAt(x.getArgument(6)).type((Boolean)x.getArgument(7)?PaymentType.DEPOSIT_REFUND:PaymentType.OTHER_CHARGE).status(PaymentStatus.AWAITING_TRANSFER).build();
            paymentData.put(p.getId(),p);return p; });
        when(payments.createMonthlyPayment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),anyString(),any())).thenAnswer(x->{
            PaymentRequest p=PaymentRequest.builder().id(UUID.randomUUID().toString()).invoiceId(x.getArgument(0)).type(PaymentType.MONTHLY_RENT)
                .payerId(x.getArgument(4)).payeeId(x.getArgument(5)).totalAmount(x.getArgument(6)).dueAt(x.getArgument(8)).status(PaymentStatus.AWAITING_TRANSFER).build();
            paymentData.put(p.getId(),p);return p;
        });
        clock("2026-12-13T10:00:00");
    }
    void clock(String local) { var now=LocalDateTime.parse(local).atZone(BillingTime.ZONE);when(time.now()).thenReturn(now.toInstant());when(time.today()).thenReturn(now.toLocalDate()); }
    ContractRenewal request() { service.requestRenewal("root","tenant",new RenewalPayload(3,"Tôi muốn tiếp tục thuê nhà."));return requests.get(0); }
    Contract approve() { var r=request();service.decideRenewal("root",r.getId(),"owner",true);return contractData.get(r.getAgreementContractId()); }
    void signed(Contract child) {
        for(var role:SignerRole.values()) when(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(child.getId(),role)).thenReturn(Optional.of(
                SignatureRequest.builder().status(SignatureRequestStatus.SIGNED).revisionId(child.getCurrentRevisionId()).build()));
        when(documents.findByContractIdAndRevisionId(child.getId(),child.getCurrentRevisionId())).thenReturn(List.of(
                ContractDocument.builder().purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build()));
    }
    void finalBills() {
        clock("2027-01-14T10:00:00");
        for(int i=0;i<3;i++)bills.add(MonthlyInvoice.builder().id("bill"+i).contractId("root").periodIndex(i).status(MonthlyInvoiceStatus.PAID).totalAmount(ZERO)
                .periodStart(rental.getMoveInDate().plusMonths(i)).periodEndExclusive(rental.getMoveInDate().plusMonths(i+1))
                .electricityEnd(new BigDecimal("1660")).waterEnd(new BigDecimal("101")).build());
    }
    ClosurePayload proposal(BigDecimal other) { return new ClosurePayload(null,null,other,"Bàn giao đủ tài sản; đã đối chiếu các khoản khấu trừ.",true); }
    void propose() { finalBills();service.proposeClosure("root","owner",proposal(new BigDecimal("100000"))); }
    AcceptClosurePayload acceptance() { return new AcceptClosurePayload(closureData.get("root").getProposalVersion(),true,true); }
    @Test void renewalOpensAtThirtyDaysAndOnlyTenantCanRequest() {
        clock("2026-12-12T23:59:59"); assertFalse(service.get("root","tenant").canRequestRenewal());
        assertThrows(AppException.class,()->request());
        clock("2026-12-13T00:00:00");assertTrue(service.get("root","tenant").canRequestRenewal());
        assertThrows(AppException.class,()->service.requestRenewal("root","owner",new RenewalPayload(3,"Nội dung hợp lệ.")));
        request();assertThrows(AppException.class,()->request());
    }
    @Test void approvalCreatesSeparateImmutableAgreementWithoutExtendingOrChargingAgain() {
        Contract child=approve();assertEquals("root",child.getParentContractId());assertEquals(ContractStatus.DRAFT,child.getStatus());
        assertNull(root.getEffectiveLeaseMonths());assertEquals("original",root.getCurrentRevisionId());
        assertEquals("{\"durationMonths\":3}",revision.getLeaseSnapshot());
        assertTrue(revisionData.get(child.getCurrentRevisionId()).getLeaseSnapshot().contains("12/01/2027"));
        verify(payments,never()).createContractSettlement(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyBoolean(),anyString());
        verify(parking).holdForRenewal(eq("root"),eq(child.getId()),eq(LocalDate.of(2027,1,12)),eq(LocalDate.of(2027,4,12)),any());
    }
    @Test void twelveMonthLeaseOpensThirtyDaysBeforeActualEndAndClosesBeforeFinalMeterDay() {
        rental.setMoveInDate(LocalDate.of(2026,10,13)); rental.setLeaseMonths(12);
        clock("2027-09-12T23:59:59"); assertFalse(service.get("root","tenant").canRequestRenewal());
        clock("2027-09-13T00:00:00");
        var result=service.get("root","tenant");
        assertEquals(LocalDate.of(2027,10,13),result.endExclusive()); assertTrue(result.canRequestRenewal());
        assertFalse(service.get("root","owner").canRequestRenewal());
        clock("2027-10-12T00:00:00"); assertFalse(service.get("root","tenant").canRequestRenewal());
        assertThrows(AppException.class,()->request());
    }
    @Test void twelveMonthLeaseRequiresFinalCostsAndConfirmedReceiptBeforeDepositProposal() {
        rental.setMoveInDate(LocalDate.of(2026,10,13)); rental.setLeaseMonths(12);
        clock("2027-10-14T10:00:00");
        for(int i=0;i<11;i++) bills.add(MonthlyInvoice.builder().id("b"+i).contractId("root").periodIndex(i)
                .status(MonthlyInvoiceStatus.PAID).totalAmount(ZERO).build());
        assertFalse(service.get("root","owner").canProposeClosure());
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",proposal(ZERO)));
        var last=MonthlyInvoice.builder().id("b11").contractId("root").periodIndex(11)
                .status(MonthlyInvoiceStatus.UNPAID).totalAmount(new BigDecimal("100000"))
                .paymentRequestId("last-payment").build();
        bills.add(last);
        var payment=PaymentRequest.builder().id("last-payment").status(PaymentStatus.TRANSFER_REPORTED).build();
        paymentData.put(payment.getId(),payment);
        assertFalse(service.get("root","owner").canProposeClosure());
        last.setStatus(MonthlyInvoiceStatus.PAID);
        assertFalse(service.get("root","owner").canProposeClosure());
        payment.setStatus(PaymentStatus.CONFIRMED);
        assertTrue(service.get("root","owner").canProposeClosure());
        assertEquals("FINAL_SETTLEMENT", service.get("root","owner").billing().bills().get(11).billingPeriod().kind());
    }
    @Test void bothVerifiedSignaturesAndFinalPdfRequiredAndActivationIsIdempotent() {
        Contract child=approve();assertThrows(AppException.class,()->service.applyRenewalAfterSignatures(child));
        signed(child); service.applyRenewalAfterSignatures(child);service.applyRenewalAfterSignatures(child);
        assertEquals(6,root.getEffectiveLeaseMonths());assertEquals("APPLIED",requests.get(0).getStatus());
        assertEquals(LocalDate.of(2027,4,12),service.get("root","tenant").endExclusive());
        assertEquals(new BigDecimal("20000000"),deposit.getHeldAmount());verify(parking,times(1)).activateRenewalHold(child.getId(),"root");
    }
    @Test void rejectedAndExpiredRequestsCannotBeApprovedAndDoNotExtendLease() {
        var r=request(); service.decideRenewal("root",r.getId(),"owner",false);assertEquals("REJECTED",r.getStatus());
        assertThrows(AppException.class,()->service.decideRenewal("root",r.getId(),"owner",true));
        assertNull(root.getEffectiveLeaseMonths());
    }
    @Test void pendingRequestExpiresAfter48Hours() {
        var r=request();clock("2026-12-15T10:00:00");service.get("root","tenant");assertEquals("EXPIRED",r.getStatus());
        assertThrows(AppException.class,()->service.decideRenewal("root",r.getId(),"owner",true));
    }
    @Test void unsignedAgreementExpiresBeforeFinalMeterDayAndReleasesOnlyRenewalParking() {
        var child=approve();clock("2027-01-11T00:00:00");service.get("root","owner");
        assertEquals("EXPIRED",requests.get(0).getStatus());assertEquals(ContractStatus.CANCELLED,child.getStatus());
        signed(child);assertThrows(AppException.class,()->service.applyRenewalAfterSignatures(child));
        assertNull(root.getEffectiveLeaseMonths());verify(parking).releaseRenewalHold(child.getId());verify(parking,never()).releaseReservationsForContract("root");
    }
    @Test void naturalExpiryDoesNotReleasePropertyOrRefundWithoutBilateralHandover() {
        clock("2027-01-12T00:00:00");var view=service.get("root","owner");assertEquals(ContractStatus.EXPIRED,root.getStatus());
        assertEquals("WAITING_HANDOVER",view.closure().getStatus());verifyNoInteractions(listings);verify(parking,never()).releaseReservationsForContract(anyString());
        assertEquals(DepositStatus.HELD,deposit.getStatus());
    }
    @Test void unsettledBillsPreventAcceptingRefundAndStaleVersionIsRejected() {
        propose();bills.get(2).setStatus(MonthlyInvoiceStatus.UNPAID);
        assertThrows(AppException.class,()->service.acceptClosure("root","tenant",acceptance()));
        bills.get(2).setStatus(MonthlyInvoiceStatus.PAID);
        assertThrows(AppException.class,()->service.acceptClosure("root","tenant",new AcceptClosurePayload(0,true,true)));
        verify(payments,never()).createContractSettlement(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyBoolean(),anyString());
    }
    @Test void tenantCanDisputeProposalWithoutMovingMoneyOrReleasingProperty() {
        propose();service.disputeClosure("root","tenant",new DisputeClosurePayload(acceptance().proposalVersion(),"Tôi chưa đồng ý khoản khấu trừ này.",List.of()));
        assertEquals("DISPUTED",closureData.get("root").getStatus());assertEquals(DepositStatus.DISPUTED,deposit.getStatus());
        assertEquals(new BigDecimal("20000000"),deposit.getHeldAmount());verifyNoInteractions(listings);
        assertThrows(AppException.class,()->service.acceptClosure("root","tenant",acceptance()));
        service.proposeClosure("root","owner",proposal(ZERO));assertEquals(2,closureData.get("root").getProposalVersion());
    }
    ClosurePayload itemized(int version, BigDecimal amount, List<ClosureDeduction> rows) {
        return new ClosurePayload(null,null,amount,"Đã bàn giao, các khoản khấu trừ chưa thu trên hóa đơn.",true,rows,version);
    }
    com.hs.storage.model.StorageObject evidence(String id,String actor) {
        var file=com.hs.storage.model.StorageObject.builder().id(id).ownerId(actor).referenceId("root")
            .referenceType("CONTRACT_CLOSURE_EVIDENCE").purpose(com.hs.storage.model.constant.StoragePurpose.GENERAL)
            .visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE).status(com.hs.storage.model.constant.StorageStatus.READY)
            .contentType("image/jpeg").sizeBytes(1024L).build();
        when(storageObjects.findById(id)).thenReturn(Optional.of(file)); return file;
    }
    @Test void itemizedProposalKeepsEvidenceAndEveryRevisionAfterDispute() {
        finalBills(); evidence("damage","owner"); evidence("counter-proof","tenant");
        var first=service.proposeClosure("root","owner",itemized(0,new BigDecimal("300000"),List.of(
            new ClosureDeduction("Vỡ kính cửa",new BigDecimal("200000"),List.of("damage")),
            new ClosureDeduction("Mất chìa khóa",new BigDecimal("100000"),List.of())))).closure();
        assertEquals(new BigDecimal("19700000"),first.getRefundAmount());
        var original=first.getHistory().get(0);
        assertEquals(List.of("damage"),original.deductions().get(0).evidenceIds());
        service.disputeClosure("root","tenant",new DisputeClosurePayload(1,"Kính đã vỡ trước khi tôi nhận nhà.",List.of("counter-proof")));
        var result=service.proposeClosure("root","owner",itemized(1,new BigDecimal("100000"),List.of(
            new ClosureDeduction("Mất chìa khóa",new BigDecimal("100000"),List.of())))).closure();
        assertEquals(2,result.getProposalVersion());assertEquals(3,result.getHistory().size());
        assertEquals(original,result.getHistory().get(0));
        assertEquals("DISPUTED",result.getHistory().get(1).type());
        assertEquals(List.of("counter-proof"),result.getHistory().get(1).evidenceIds());
        assertThrows(AppException.class,()->service.acceptClosure("root","tenant",new AcceptClosurePayload(1,true,true)));
        verifyNoInteractions(listings); assertTrue(paymentData.isEmpty());
        service.acceptClosure("root","tenant",acceptance());
        assertEquals(new BigDecimal("19900000"),paymentData.get("settlement").getTotalAmount());
    }
    @Test void deductionsRejectMismatchedTotalsDuplicatesNegativeAndStaleVersions() {
        finalBills(); var row=new ClosureDeduction("Khấu trừ hợp lệ",new BigDecimal("100000"),List.of());
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",itemized(0,ZERO,List.of(row))));
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",itemized(0,new BigDecimal("200000"),List.of(row,row))));
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",itemized(0,ZERO,List.of(new ClosureDeduction("Âm tiền",new BigDecimal("-1"),List.of())))));
        service.proposeClosure("root","owner",itemized(0,ZERO,List.of()));
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",itemized(0,ZERO,List.of())));
        assertEquals(1,service.get("root","owner").closure().getHistory().size());
    }
    @Test void evidenceMustBePrivateReadyOwnedBySenderAndBoundToThisContract() {
        finalBills(); var file=evidence("photo","tenant");
        var payload=itemized(0,new BigDecimal("100000"),List.of(new ClosureDeduction("Vỡ kính cửa",new BigDecimal("100000"),List.of("photo"))));
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",payload));
        file.setOwnerId("owner"); file.setReferenceId("another-contract");
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",payload));
        file.setReferenceId("root");file.setVisibility(com.hs.storage.model.constant.StorageVisibility.PUBLIC);
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",payload));
        file.setVisibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE);file.setStatus(com.hs.storage.model.constant.StorageStatus.DELETED);
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",payload));
        file.setStatus(com.hs.storage.model.constant.StorageStatus.READY);file.setSizeBytes(16*1024*1024L);
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",payload));
        file.setSizeBytes(1024L);file.setContentType("text/html");
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",payload));
        file.setContentType("application/pdf");service.proposeClosure("root","owner",payload);
        assertEquals(1,service.get("root","tenant").closure().getDeductions().size());
    }
    @Test void disputeRequiresTenantReasonAndCurrentProposalAndCannotBeRepeated() {
        propose();
        assertThrows(AppException.class,()->service.disputeClosure("root","owner",new DisputeClosurePayload(1,"Tôi không đồng ý đề xuất này.",List.of())));
        assertThrows(AppException.class,()->service.disputeClosure("root","tenant",new DisputeClosurePayload(0,"Tôi không đồng ý đề xuất này.",List.of())));
        assertThrows(AppException.class,()->service.disputeClosure("root","tenant",new DisputeClosurePayload(1," ",List.of())));
        service.disputeClosure("root","tenant",new DisputeClosurePayload(1,"Tôi không đồng ý đề xuất này.",List.of()));
        assertThrows(AppException.class,()->service.disputeClosure("root","tenant",new DisputeClosurePayload(1,"Tôi không đồng ý đề xuất này.",List.of())));
        assertTrue(paymentData.isEmpty());
    }
    @Test void responseDeadlineMarksAttentionOnceButNeverCreatesQrOrAssumesConsent() {
        propose(); assertEquals(Instant.parse("2027-01-16T03:00:00Z"),closureData.get("root").getRespondBy());
        clock("2027-01-16T09:59:59"); assertNull(service.get("root","tenant").closure().getResponseOverdueAt());
        clock("2027-01-16T10:00:00"); var close=service.get("root","owner").closure();
        assertNotNull(close.getResponseOverdueAt()); assertEquals("PROPOSED",close.getStatus());
        service.get("root","tenant");assertEquals(2,close.getHistory().size());
        service.disputeClosure("root","tenant",new DisputeClosurePayload(1,"Tôi chưa đồng ý khoản khấu trừ.",List.of()));
        assertNull(close.getResponseOverdueAt()); assertEquals(Instant.parse("2027-01-18T03:00:00Z"),close.getRespondBy());
        clock("2027-01-18T10:00:00");service.get("root","owner");
        assertEquals("DISPUTED",close.getStatus()); assertNotNull(close.getResponseOverdueAt());assertNull(close.getAcceptedAt());
        assertTrue(paymentData.isEmpty());verifyNoInteractions(listings);
        service.proposeClosure("root","owner",itemized(1,ZERO,List.of()));
        service.acceptClosure("root","tenant",acceptance());assertNull(close.getRespondBy());
    }
    @Test void legacyProposalIsPreservedAndStorageSnapshotsAreNotExposedAsRawJson() throws Exception {
        propose();var close=closureData.get("root");close.setHistorySnapshot(null);close.setDeductionsSnapshot(null);
        service.proposeClosure("root","owner",itemized(1,ZERO,List.of()));
        var response=service.get("root","tenant");assertEquals("LEGACY_PROPOSAL",response.closure().getHistory().get(0).type());
        assertEquals(new BigDecimal("100000"),response.closure().getHistory().get(0).deductions().get(0).amount());
        String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertFalse(json.contains("historySnapshot")); assertFalse(json.contains("deductionsSnapshot"));
        assertTrue(json.contains("\"history\""));assertTrue(json.contains("\"deductions\""));
    }
    static final BigDecimal ZERO=BigDecimal.ZERO;
    @Test void agreedSettlementDoesNotDeductUtilitiesAndCreatesReverseTransferWithDeadline() {
        propose();assertEquals(ZERO,closureData.get("root").getUtilityAdjustment());
        service.acceptClosure("root","tenant",acceptance());var close=closureData.get("root");
        assertEquals(new BigDecimal("19900000"),close.getRefundAmount());assertEquals("REFUND_PENDING",close.getStatus());
        assertEquals(Instant.parse("2027-01-29T03:00:00Z"),close.getRefundDueAt());
        var p=paymentData.get("settlement");assertEquals("owner",p.getPayerId());assertEquals("tenant",p.getPayeeId());
        assertEquals(DepositStatus.REFUND_DUE,deposit.getStatus());verify(listings).releaseRentedAfterTermination("listing","owner");
        assertEquals(ContractStatus.EXPIRED,root.getStatus());assertThrows(AppException.class,()->service.acceptClosure("root","tenant",acceptance()));
    }
    @Test void submittedRefundProofIsNotReceiptAndOnlyConfirmedRefundClosesContract() {
        propose();service.acceptClosure("root","tenant",acceptance());
        paymentData.get("settlement").setStatus(PaymentStatus.TRANSFER_REPORTED);service.get("root","tenant");
        assertEquals("REFUND_PENDING",closureData.get("root").getStatus());
        paymentData.get("settlement").setStatus(PaymentStatus.CONFIRMED);service.get("root","tenant");
        assertEquals("CLOSED",closureData.get("root").getStatus());assertEquals(ContractStatus.CLOSED,root.getStatus());
    }
    @Test void overdueRefundIsNeverAutomaticallyConfirmedOrCancelled() {
        propose();service.acceptClosure("root","tenant",acceptance());clock("2027-01-30T10:00:00");
        service.get("root","owner");assertEquals("REFUND_PENDING",closureData.get("root").getStatus());
        assertNotNull(closureData.get("root").getOverdueLoggedAt());assertNull(closureData.get("root").getClosedAt());
    }
    @Test void zeroRefundCreatesNoZeroQrAndExcessDeductionsCreateTenantBalance() {
        finalBills();service.proposeClosure("root","owner",proposal(new BigDecimal("20051000")));
        service.acceptClosure("root","tenant",acceptance());var close=closureData.get("root");
        assertEquals(ZERO,close.getRefundAmount());assertEquals(new BigDecimal("51000"),close.getBalanceAmount());
        assertEquals("BALANCE_PENDING",close.getStatus());assertNull(close.getRefundPaymentId());
        assertEquals("tenant",paymentData.get("settlement").getPayerId());
    }
    @Test void noDeductionsBeyondHeldCreatesClosedSettlementWithoutTransfer() {
        finalBills();service.proposeClosure("root","owner",new ClosurePayload(new BigDecimal("1660"),new BigDecimal("101"),new BigDecimal("20000000"),"Hai bên thống nhất khấu trừ toàn bộ cọc.",true));
        service.acceptClosure("root","tenant",acceptance());assertEquals(ContractStatus.CLOSED,root.getStatus());
        verify(payments,never()).createContractSettlement(anyString(),anyString(),anyString(),anyString(),anyString(),any(),any(),anyBoolean(),anyString());
    }
    @Test void ownershipAndRealMeterValidationCannotBeBypassed() {
        assertThrows(AppException.class,()->service.get("root","stranger"));finalBills();
        assertThrows(AppException.class,()->service.proposeClosure("root","tenant",proposal(ZERO)));
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",new ClosurePayload(new BigDecimal("1659"),new BigDecimal("101"),ZERO,"Bàn giao các tài sản đầy đủ.",true)));
    }
    @Test void monthEndRenewalKeepsOriginalAnchorRatherThanDrifting() {
        rental.setMoveInDate(LocalDate.of(2026,8,31));clock("2026-10-31T10:00:00");
        service.requestRenewal("root","tenant",new RenewalPayload(2,"Tôi muốn gia hạn hai tháng."));
        assertEquals(LocalDate.of(2027,1,31),requests.get(0).getEndExclusive());
    }
    @Test void earlyTerminationBlocksRenewalActivationAndReleasesPendingChildHoldWhenFinalized() {
        Contract child=approve();signed(child);root.setTerminationProposedAt(time.now());
        assertThrows(AppException.class,()->service.applyRenewalAfterSignatures(child));
        assertNull(root.getEffectiveLeaseMonths());
        root.setStatus(ContractStatus.TERMINATED);
        service.cancelPendingRenewalsForClosedParent(root);
        assertEquals("EXPIRED",requests.get(0).getStatus());assertEquals(ContractStatus.CANCELLED,child.getStatus());
        verify(parking).releaseRenewalHold(child.getId());
    }
    @Test void lifecycleSchedulerUsesStableIdPagesWhenStatusesChange() {
        clock("2027-01-12T00:00:00");
        when(contracts.findLifecycleIdsAfter(any(),isNull(),any())).thenReturn(List.of("root"));
        when(contracts.findLifecycleIdsAfter(any(),eq("root"),any())).thenReturn(List.of());
        service.scheduledSync();
        assertEquals(ContractStatus.EXPIRED,root.getStatus());
        assertEquals("WAITING_HANDOVER",closureData.get("root").getStatus());
        verify(contracts).findLifecycleIdsAfter(any(),eq("root"),any());verify(contracts,never()).findAll();
    }
    @Test void unpaidFinalBillOrOnlyReportedProofBlocksProposalAndRefund() {
        finalBills();MonthlyInvoice last=bills.get(2);last.setTotalAmount(new BigDecimal("255000"));last.setPaymentRequestId("bill-payment");
        last.setStatus(MonthlyInvoiceStatus.UNPAID);
        PaymentRequest p=PaymentRequest.builder().id("bill-payment").status(PaymentStatus.TRANSFER_REPORTED).build();paymentData.put(p.getId(),p);
        assertFalse(service.get("root","owner").canProposeClosure());
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",proposal(ZERO)));
        assertEquals(new BigDecimal("20000000"),deposit.getHeldAmount());
        p.setStatus(PaymentStatus.CONFIRMED);p.setConfirmedAt(time.now());
        assertTrue(service.get("root","owner").canProposeClosure());assertEquals(MonthlyInvoiceStatus.PAID,last.getStatus());
        service.proposeClosure("root","owner",proposal(ZERO));service.acceptClosure("root","tenant",acceptance());
        assertEquals(new BigDecimal("20000000"),closureData.get("root").getRefundAmount());
    }
    @Test void missingFinalInvoiceBlocksDepositProposal() {
        finalBills();bills.remove(2);
        assertFalse(service.get("root","owner").canProposeClosure());
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",proposal(ZERO)));
    }
    @Test void utilityDifferenceCannotBeSmuggledIntoDepositDeduction() {
        finalBills();assertThrows(AppException.class,()->service.proposeClosure("root","owner",
            new ClosurePayload(new BigDecimal("1670"),new BigDecimal("102"),ZERO,"Bàn giao đúng tài sản thuê.",true)));
        assertEquals(new BigDecimal("20000000"),deposit.getHeldAmount());verifyNoInteractions(listings);
    }
    @Test void finalCostsCreateNormalSupplementBillWithoutRentAndRefundWaitsForActualReceipt() {
        finalBills();var payload=new FinalCostsPayload(UUID.randomUUID().toString(),new BigDecimal("1670"),new BigDecimal("102"),
            List.of(new IssueMonthlyInvoiceRequest.ExtraCharge("Chi phí phát sinh chưa thu",new BigDecimal("100000"))));
        service.issueFinalCosts("root","owner",payload);
        MonthlyInvoice bill=bills.get(3);assertTrue(bill.isFinalCostSupplement());assertEquals("bill2",bill.getSourceInvoiceId());
        assertEquals(new BigDecimal("151000"),bill.getTotalAmount());assertEquals(Instant.parse("2027-01-18T16:59:59Z"),bill.getDueAt());
        assertFalse(bill.getLineItemsSnapshot().contains("RENT"));
        assertEquals(new BigDecimal("20000000"),deposit.getHeldAmount());
        assertThrows(AppException.class,()->service.proposeClosure("root","owner",proposal(ZERO)));
        service.issueFinalCosts("root","owner",payload);assertEquals(4,bills.size());
        var p=paymentData.get(bill.getPaymentRequestId());p.setStatus(PaymentStatus.TRANSFER_REPORTED);
        assertFalse(service.get("root","tenant").billing().allPaid());
        p.setStatus(PaymentStatus.CONFIRMED);p.setConfirmedAt(time.now());service.get("root","owner");
        service.proposeClosure("root","owner",proposal(ZERO));service.acceptClosure("root","tenant",acceptance());
        assertEquals(new BigDecimal("20000000"),closureData.get("root").getRefundAmount());
        assertThrows(AppException.class,()->service.issueFinalCosts("root","owner",new FinalCostsPayload(UUID.randomUUID().toString(),new BigDecimal("1680"),null,List.of())));
    }
    @Test void addingSupplementInvalidatesReadProposalAndRejectsWrongActorOrLowerMeters() {
        propose();int version=closureData.get("root").getProposalVersion();
        assertThrows(AppException.class,()->service.issueFinalCosts("root","tenant",new FinalCostsPayload(UUID.randomUUID().toString(),new BigDecimal("1670"),null,List.of())));
        assertThrows(AppException.class,()->service.issueFinalCosts("root","owner",new FinalCostsPayload(UUID.randomUUID().toString(),new BigDecimal("1659"),null,List.of())));
        service.issueFinalCosts("root","owner",new FinalCostsPayload(UUID.randomUUID().toString(),new BigDecimal("1670"),null,List.of()));
        assertEquals("WAITING_HANDOVER",closureData.get("root").getStatus());assertTrue(closureData.get("root").getProposalVersion()>version);
        assertThrows(AppException.class,()->service.acceptClosure("root","tenant",new AcceptClosurePayload(version,true,true)));
    }
    @Test void confirmedInvoiceClosesPendingExtensionAndCancelsUnfinishedTerminationProposal() throws Exception {
        finalBills();var bill=bills.get(2);bill.setStatus(MonthlyInvoiceStatus.UNPAID);bill.setTotalAmount(new BigDecimal("255000"));bill.setPaymentRequestId("payment");
        var extension=new PaymentExtension("extension",7,"Xin thêm thời gian thanh toán",PaymentExtension.Status.PENDING,
            time.now(),time.now().plusSeconds(3600),"tenant",time.now(),ZERO,null,null,null);
        bill.setExtensionRequestsSnapshot(new ObjectMapper().findAndRegisterModules().writeValueAsString(List.of(extension)));
        root.setTerminationProposedAt(time.now());root.setTerminationProposalInvoiceId(bill.getId());
        paymentData.put("payment",PaymentRequest.builder().id("payment").status(PaymentStatus.CONFIRMED).confirmedAt(time.now()).build());
        service.get("root","owner");
        assertEquals(MonthlyInvoiceStatus.PAID,bill.getStatus());assertTrue(bill.getExtensionRequestsSnapshot().contains("CLOSED"));
        assertNotNull(root.getTerminationCancelledAt());
    }
}
