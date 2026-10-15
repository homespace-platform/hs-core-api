package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.amendment.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.ContractAmendmentRenderer;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ParkingReservationService;
import com.hs.storage.repository.StorageObjectRepository;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContractAmendmentServiceTest {
    ContractRepository contracts = mock(ContractRepository.class);
    ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
    ContractAmendmentRepository amendments = mock(ContractAmendmentRepository.class);
    ContractRenewalRepository renewals = mock(ContractRenewalRepository.class);
    MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    SignatureRequestRepository signatures = mock(SignatureRequestRepository.class);
    ContractDocumentRepository documents = mock(ContractDocumentRepository.class);
    StorageObjectRepository files = mock(StorageObjectRepository.class);
    ParkingReservationService parking = mock(ParkingReservationService.class);
    BillingTime time = mock(BillingTime.class);
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AmendmentTermsBuilder builder = new AmendmentTermsBuilder(mapper);
    ContractTermsResolver resolver = new ContractTermsResolver(amendments, revisions);
    ContractAmendmentService service;
    Contract root;
    ContractRevision original;
    Map<String,Contract> contractStore = new HashMap<>();
    Map<String,ContractRevision> revisionStore = new HashMap<>();
    List<ContractAmendment> proposals = new ArrayList<>();
    List<MonthlyInvoice> bills = new ArrayList<>();
    ContractAmendmentDraftRepository drafts = mock(ContractAmendmentDraftRepository.class);
    Map<String,ContractAmendmentDraft> draftStore = new HashMap<>();

    @BeforeEach void setup() {
        at("2026-10-14T03:00:00Z");
        root = Contract.builder().id("root").contractNumber("HD-20261014-DEMO").rentalRequestId("rental")
                .listingId("listing").landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("v1")
                .currentRevisionId("original").status(ContractStatus.ACTIVE).paymentStatus(ContractPaymentStatus.PAID).build();
        original = ContractRevision.builder().id("original").contract(root).revisionNumber(2).schemaVersion(2).templateVersionId("v1")
                .landlordSnapshot("{\"fullName\":\"Chủ nhà\",\"idNumber\":\"123\"}")
                .tenantSnapshot("{\"fullName\":\"Người thuê\",\"idNumber\":\"456\",\"occupantCount\":4,\"motorbikeCount\":0,\"carCount\":0}")
                .propertySnapshot("{\"maxOccupants\":4,\"fullAddress\":\"Nhà số 2\"}").leaseSnapshot("{\"durationMonths\":3}")
                .financialSnapshot("{\"amountValue\":\"12400000\",\"amountNumber\":\"12.400.000 VNĐ/tháng\",\"depositAmountValue\":\"24800000\"}")
                .chargesSnapshot("[{\"name\":\"WiFi\",\"chargeType\":\"INTERNET\",\"billingMethod\":\"PER_MONTH\",\"unitAmount\":\"90000\",\"estimatedMonthlyAmount\":\"90000\",\"includedInRent\":false,\"amountAndMethod\":\"90.000 / tháng\"},"
                        + "{\"name\":\"Vệ sinh\",\"chargeType\":\"SERVICE_OR_GARBAGE\",\"billingMethod\":\"PER_PERSON_MONTH\",\"unitAmount\":\"30000\",\"estimatedMonthlyAmount\":\"120000\",\"includedInRent\":false,\"amountAndMethod\":\"30.000 × 4 = 120.000\"}]")
                .equipmentSnapshot("[]").amenitiesSnapshot("[]").policiesSnapshot("{\"latePaymentFeeMode\":\"NONE\",\"petsAllowed\":false}")
                .specialTerms("Điều khoản gốc").initialMetersSnapshot("{\"electricityInitial\":1245,\"waterInitial\":42}")
                .initialPaymentSnapshot("{\"status\":\"CONFIRMED\",\"totalAmount\":37200000}").build();
        contractStore.put("root", root); revisionStore.put("original", original);
        when(contracts.findById(anyString())).thenAnswer(i -> Optional.ofNullable(contractStore.get(i.getArgument(0))));
        when(contracts.findByIdForUpdate(anyString())).thenAnswer(i -> Optional.ofNullable(contractStore.get(i.getArgument(0))));
        when(contracts.save(any())).thenAnswer(i -> { Contract c = i.getArgument(0); c.prePersist(); contractStore.put(c.getId(),c); return c; });
        when(revisions.findById(anyString())).thenAnswer(i -> Optional.ofNullable(revisionStore.get(i.getArgument(0))));
        when(revisions.save(any())).thenAnswer(i -> { ContractRevision r = i.getArgument(0); r.prePersist(); revisionStore.put(r.getId(),r); return r; });
        when(amendments.findByContractIdOrderByCreatedAtDesc("root")).thenAnswer(i -> new ArrayList<>(proposals));
        when(amendments.findById(anyString())).thenAnswer(i -> proposals.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        when(amendments.findByAgreementContractId(anyString())).thenAnswer(i -> proposals.stream().filter(a -> Objects.equals(a.getAgreementContractId(),i.getArgument(0))).findFirst());
        when(amendments.save(any())).thenAnswer(i -> { ContractAmendment a = i.getArgument(0); if (!proposals.contains(a)) proposals.add(0,a); return a; });
        when(invoices.findByContractIdOrderByPeriodIndexDesc("root")).thenAnswer(i -> bills);
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(LocalDate.of(2026,10,14)).leaseMonths(3).build()));
        service = new ContractAmendmentService(contracts,revisions,amendments,renewals,invoices,rentals,signatures,documents,files,parking,
                builder,resolver,mapper,time,mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service,"signatureMode","SMARTCA");
        ReflectionTestUtils.setField(service,"drafts",drafts);
        when(drafts.findById(anyString())).thenAnswer(i -> Optional.ofNullable(draftStore.get(i.getArgument(0))));
        when(drafts.save(any())).thenAnswer(i -> { ContractAmendmentDraft d = i.getArgument(0); draftStore.put(d.getContractId(),d); return d; });
    }
    void at(String instant) { Instant now = Instant.parse(instant); when(time.now()).thenReturn(now); when(time.today()).thenReturn(now.atZone(BillingTime.ZONE).toLocalDate()); }
    AmendmentPayload payload(Map<String,Object> changes) { return new AmendmentPayload(UUID.randomUUID().toString(),"original",null,LocalDate.of(2026,11,14),"Hai bên thỏa thuận thay đổi",changes,List.of()); }
    ContractAmendment request(String actor) { service.propose("root",actor,payload(Map.of("rentAmount","12000000"))); return proposals.get(0); }
    Contract approve(ContractAmendment a) { var response=service.decide("root",a.getId(),a.getRecipientId(),new AmendmentDecision("APPROVE",null)); assertEquals(a.getDecidedAt(),response.proposals().stream().filter(p->p.id().equals(a.getId())).findFirst().orElseThrow().decidedAt()); return contractStore.get(a.getAgreementContractId()); }
    void signaturesReady(Contract child) {
        for (SignerRole role : SignerRole.values()) when(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(child.getId(),role))
                .thenReturn(Optional.of(SignatureRequest.builder().contractId(child.getId()).revisionId(child.getCurrentRevisionId()).signerRole(role).status(SignatureRequestStatus.SIGNED).build()));
        when(documents.findByContractIdAndRevisionId(child.getId(),child.getCurrentRevisionId())).thenReturn(List.of(ContractDocument.builder().purpose(DocumentPurpose.SIGNED_FINAL).status(DocumentGenerationStatus.READY).build()));
    }
    @Test void freeFormAmendmentUsesUpdatedSnapshotsAndRequiresBothSignaturesBeforeApplying() {
        var choices=mock(AmendmentOptionsService.class);ReflectionTestUtils.setField(service,"options",choices);
        var changes=Map.<String,Object>of("freeForm",true,"fields",Map.of("property",Map.of("floor","Tầng mái"),"landlord",Map.of("phone","0901234567")),
                "equipments",List.of(Map.of("name","Piano không có trên Listing","quantity",1,"condition","Mới","note","Hai bên thống nhất")));
        service.propose("root","owner",payload(changes));var offer=proposals.get(0);var child=approve(offer);
        verify(choices,never()).get(any(),any());verify(choices,never()).validate(any(),any(),any());
        var revised=revisionStore.get(child.getCurrentRevisionId());assertEquals("Tầng mái",builder.map(revised.getPropertySnapshot()).get("floor"));
        assertEquals("0901234567",builder.map(revised.getLandlordSnapshot()).get("phone"));
        assertEquals(original.getInitialPaymentSnapshot(),revised.getInitialPaymentSnapshot());assertEquals(original.getInitialMetersSnapshot(),revised.getInitialMetersSnapshot());
        assertEquals("original",root.getCurrentRevisionId());assertEquals("original",resolver.at(root,LocalDate.of(2026,11,14)).getId());
        assertThrows(AppException.class,()->service.signed(child));signaturesReady(child);service.signed(child);
        assertEquals(revised.getId(),resolver.at(root,LocalDate.of(2026,11,14)).getId());
        assertEquals("original",resolver.at(root,LocalDate.of(2026,10,14)).getId());
    }
    @Test void landlordProposesAndTenantApprovesWithoutChangingSignedRoot() {
        for (String actor : List.of("owner")) {
            proposals.clear(); var a = request(actor); var child = approve(a);
            assertEquals(actor,a.getProposerId()); assertNotEquals(actor,a.getRecipientId());
            assertEquals("AMENDMENT",child.getAgreementKind()); assertEquals("root",child.getParentContractId());
            var adjusted = revisionStore.get(child.getCurrentRevisionId());
            assertEquals("12000000",builder.map(adjusted.getFinancialSnapshot()).get("amountValue"));
            assertEquals(original.getInitialPaymentSnapshot(),adjusted.getInitialPaymentSnapshot());
            assertEquals(original.getInitialMetersSnapshot(),adjusted.getInitialMetersSnapshot());
            assertEquals("original",root.getCurrentRevisionId()); assertEquals(ContractStatus.ACTIVE,root.getStatus()); assertNull(root.getEffectiveLeaseMonths());
        }
    }
    @Test void signedLeaseChangeUpdatesFutureBillingLengthNotThePaidRentalOrRootRevision() {
        original.setLeaseSnapshot("{\"startDateText\":\"14/10/2026\",\"endDateText\":\"13/01/2027\",\"durationMonths\":3}");
        var change=Map.<String,Object>of("freeForm",true,"fields",Map.of("lease",Map.of("startDateText","14/11/2026","durationMonths",3)));
        service.propose("root","owner",payload(change));var a=proposals.getFirst();var child=approve(a);
        assertNull(root.getEffectiveLeaseMonths());signaturesReady(child);service.signed(child);
        assertEquals(4,root.getEffectiveLeaseMonths());assertEquals("original",root.getCurrentRevisionId());
        assertEquals(LocalDate.of(2026,10,14),rentals.findById("rental").orElseThrow().getMoveInDate());
        assertEquals("13/02/2027",builder.map(resolver.at(root,LocalDate.of(2026,11,14)).getLeaseSnapshot()).get("endDateText"));
        verify(parking).activateAmendmentHold(eq("root"),eq(child.getId()),eq("rental"),eq("listing"),eq(LocalDate.of(2026,11,14)),eq(LocalDate.of(2027,2,14)),eq(0),eq(0));
    }
    @Test void startChangeCannotMovePaidHistoryAndPrepaidRentBlocksOverlappingEffectiveDates() {
        original.setLeaseSnapshot("{\"startDateText\":\"14/10/2026\",\"endDateText\":\"13/01/2027\",\"durationMonths\":3}");
        assertThrows(AppException.class,()->service.preview("root","owner",payload(Map.of("freeForm",true,"fields",Map.of("lease",Map.of("startDateText","01/10/2026"))))));
        bills.add(MonthlyInvoice.builder().periodStart(LocalDate.of(2026,10,14)).periodEndExclusive(LocalDate.of(2026,11,14)).rentPeriodEndExclusive(LocalDate.of(2027,1,14)).status(MonthlyInvoiceStatus.PAID).build());
        assertEquals(List.of(),service.get("root","owner").availableDates());
    }
    @Test void signedDepositChangeCreatesOnlyDeltaOnEffectiveDateAndRetriesNeverDuplicateIt() {
        var payments=mock(com.hs.payment.service.PaymentRequestService.class);var deposits=mock(com.hs.payment.repository.DepositRecordRepository.class);
        ReflectionTestUtils.setField(service,"payments",payments);ReflectionTestUtils.setField(service,"deposits",deposits);
        var record=com.hs.payment.model.DepositRecord.builder().status(com.hs.payment.model.constant.DepositStatus.HELD).heldAmount(new java.math.BigDecimal("24800000")).build();
        when(deposits.findByRentalRequestIdForUpdate("rental")).thenReturn(Optional.of(record));
        service.propose("root","owner",payload(Map.of("freeForm",true,"fields",Map.of("financial",Map.of("depositAmountValue","30000000")))));
        var a=proposals.getFirst();var child=approve(a);signaturesReady(child);service.signed(child);
        verify(payments,never()).createDepositAdjustment(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any(),any());
        at("2026-11-14T03:00:00Z");service.get("root","owner");service.get("root","owner");
        verify(payments,times(1)).createDepositAdjustment(eq(a.getId()),eq("root"),eq("rental"),eq("listing"),eq("owner"),eq("tenant"),eq(new java.math.BigDecimal("5200000")),any());
        assertEquals(new java.math.BigDecimal("24800000"),record.getHeldAmount());assertEquals("APPLIED",a.getStatus());
    }
    @Test void outsiderAndSelfApprovalAreDenied() {
        assertThrows(AppException.class,() -> service.get("root","outsider"));
        var a = request("owner");
        assertThrows(AppException.class,() -> service.decide("root",a.getId(),"owner",new AmendmentDecision("APPROVE",null)));
        assertEquals("REQUESTED",a.getStatus());
    }
    @Test void duplicatePostReusesProposalButRejectsChangedBody() {
        var p = payload(Map.of("rentAmount","12000000"));
        service.propose("root","owner",p); service.propose("root","owner",p); assertEquals(1,proposals.size());
        assertThrows(AppException.class,() -> service.propose("root","owner",new AmendmentPayload(p.requestId(),p.baseRevisionId(),null,p.effectiveOn(),p.reason(),Map.of("rentAmount","11000000"),List.of())));
    }
    @Test void concurrentSecondProposalIsBlocked() { request("owner"); assertThrows(AppException.class,() -> request("owner")); assertEquals(1,proposals.size()); }
    @Test void counterRequestCreatesImmutableNewVersionAndRequiresNewConsent() {
        var a = request("owner");
        service.decide("root",a.getId(),"tenant",new AmendmentDecision("REQUEST_CHANGES","Đề nghị giảm thêm tiền thuê"));
        var p = payload(Map.of("rentAmount","11500000"));
        service.propose("root","owner",new AmendmentPayload(p.requestId(),p.baseRevisionId(),a.getId(),p.effectiveOn(),p.reason(),p.changes(),List.of()));
        assertEquals("SUPERSEDED",a.getStatus()); assertTrue(a.getChangesSnapshot().contains("12000000"));
        assertEquals("REQUESTED",proposals.get(0).getStatus());
        assertThrows(AppException.class,() -> service.decide("root",a.getId(),"tenant",new AmendmentDecision("APPROVE",null)));
    }
    @Test void rejectionAndWithdrawalAllowNewRequests() {
        var a = request("owner"); service.decide("root",a.getId(),"tenant",new AmendmentDecision("REJECT","Không đồng ý với giá đề nghị"));
        var next = request("owner"); service.decide("root",next.getId(),"owner",new AmendmentDecision("WITHDRAW",null));
        assertTrue(service.get("root","owner").canPropose());
    }
    @Test void responseExpiryDoesNotAcceptOrCreateSigningDocument() {
        var a = request("owner"); at("2026-10-16T03:00:00Z"); service.get("root","owner");
        assertEquals("EXPIRED",a.getStatus()); assertNull(a.getAgreementContractId()); assertEquals("original",root.getCurrentRevisionId());
        assertThrows(AppException.class,() -> service.decide("root",a.getId(),"owner",new AmendmentDecision("APPROVE",null)));
    }
    @Test void signingExpiryKeepsSingleSignatureAndCancelsUnappliedAppendix() {
        var a = request("owner"); var child = approve(a); at("2026-10-16T03:00:00Z");
        service.get("root","tenant"); assertEquals("EXPIRED",a.getStatus()); assertEquals(ContractStatus.CANCELLED,child.getStatus());
        verify(parking).releaseRenewalHold(child.getId()); assertEquals("original",resolver.at(root,LocalDate.of(2026,11,14)).getId());
        verify(documents,never()).delete(any());
    }
    @Test void bothSignaturesAndFinalPdfRequiredForScheduledTerms() {
        var a = request("owner"); var child = approve(a);
        assertThrows(AppException.class,() -> service.signed(child)); assertEquals("APPROVED",a.getStatus());
        signaturesReady(child); when(documents.findByContractIdAndRevisionId(child.getId(),child.getCurrentRevisionId())).thenReturn(List.of());
        assertThrows(AppException.class,() -> service.signed(child)); assertEquals("APPROVED",a.getStatus());
        signaturesReady(child); service.signed(child); service.signed(child);
        assertEquals("SCHEDULED",a.getStatus()); assertEquals("original",resolver.at(root,LocalDate.of(2026,10,14)).getId());
        assertEquals(child.getCurrentRevisionId(),resolver.at(root,LocalDate.of(2026,11,14)).getId());
        at("2026-11-14T03:00:00Z"); service.get("root","tenant"); assertEquals("APPLIED",a.getStatus());
        assertEquals("original",root.getCurrentRevisionId()); assertTrue(service.get("root","tenant").canPropose());
    }
    @Test void issuedInvoiceAndStaleBaseBlockApprovalAndSignatures() {
        var a = request("owner");
        bills.add(MonthlyInvoice.builder().periodStart(LocalDate.of(2026,10,14)).periodEndExclusive(LocalDate.of(2026,11,14)).status(MonthlyInvoiceStatus.UNPAID).build());
        assertThrows(AppException.class,() -> approve(a)); assertEquals("REQUESTED",a.getStatus());
    }
    @Test void renewalAndTerminationConflictsAreRejected() {
        Instant deadline = time.now().plusSeconds(3600);
        when(renewals.findByContractIdOrderByRequestedAtDesc("root")).thenReturn(List.of(ContractRenewal.builder().status("REQUESTED").signBy(deadline).build()));
        assertFalse(service.get("root","tenant").canPropose()); assertThrows(AppException.class,() -> request("owner"));
        when(renewals.findByContractIdOrderByRequestedAtDesc("root")).thenReturn(List.of());
        root.setTerminationProposedAt(time.now()); assertThrows(AppException.class,() -> request("owner"));
    }
    @Test void occupancyRecalculatesOnlyFuturePerPersonFees() {
        var p = payload(Map.of("occupantCount",3)); service.propose("root","owner",p); var child = approve(proposals.get(0));
        var rev = revisionStore.get(child.getCurrentRevisionId());
        assertEquals("90000",builder.rows(rev.getChargesSnapshot()).get(1).get("estimatedMonthlyAmount"));
        assertEquals("120000",builder.rows(original.getChargesSnapshot()).get(1).get("estimatedMonthlyAmount"));
        verifyNoInteractions(parking);
    }
    @Test void appendixDocxContainsOnlyAgreedChangesAndOriginalReference() throws Exception {
        var a = request("owner"); var child = approve(a);
        byte[] bytes = new ContractAmendmentRenderer(contracts,amendments,service,builder).render(child,revisionStore.get(child.getCurrentRevisionId()));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = String.join("\n",doc.getParagraphs().stream().map(p -> p.getText()).toList());
            assertTrue(text.contains("PHỤ LỤC ĐIỀU CHỈNH")); assertTrue(text.contains(root.getContractNumber()));
            assertTrue(text.contains("14/11/2026")); assertTrue(text.contains("12000000")); assertTrue(text.contains("12400000"));
            assertFalse(text.contains("HỢP ĐỒNG THUÊ NHÀ NGUYÊN CĂN"));
        }
    }
    @Test void invalidDatesUnchangedValuesAndProtectedFieldsAreRejected() {
        assertThrows(AppException.class,() -> service.propose("root","owner",payload(Map.of("rentAmount","12400000"))));
        assertThrows(AppException.class,() -> service.propose("root","owner",payload(Map.of("tenant",Map.of("idNumber","forged")))));
        var p = payload(Map.of("rentAmount","12000000"));
        assertThrows(AppException.class,() -> service.propose("root","owner",new AmendmentPayload(p.requestId(),"stale",null,p.effectiveOn(),p.reason(),p.changes(),List.of())));
        assertThrows(AppException.class,() -> service.propose("root","owner",new AmendmentPayload(p.requestId(),p.baseRevisionId(),null,LocalDate.of(2026,10,15),p.reason(),p.changes(),List.of())));
    }
    @Test void expiredOrClosedRootNeverAcceptsNewAmendments() { for (ContractStatus status : List.of(ContractStatus.EXPIRED,ContractStatus.CLOSED,ContractStatus.TERMINATED)) { root.setStatus(status); assertThrows(AppException.class,() -> request("owner")); } }
    @Test void nonFinancialAppendixAlsoWaitsForNextPeriodAndImmediateIsRejectedEverywhere() {
        var immediatePayload = new AmendmentPayload(UUID.randomUUID().toString(),"original",null,null,"Hai bên đồng ý nuôi vật nuôi",
                Map.of("applyImmediately",true,"policies",Map.of("petsAllowed",true)),List.of());
        assertThrows(AppException.class,()->service.preview("root","owner",immediatePayload));
        assertThrows(AppException.class,()->service.propose("root","owner",immediatePayload));
        assertThrows(AppException.class,()->service.saveDraft("root","owner",immediatePayload));
        var p=payload(Map.of("policies",Map.of("petsAllowed",true)));
        service.propose("root","owner",p); var a = proposals.get(0); var child = approve(a);
        at("2026-10-15T03:00:00Z"); signaturesReady(child); service.signed(child);
        assertEquals("SCHEDULED",a.getStatus()); assertNull(a.getAppliedAt());
        assertEquals(LocalDate.of(2026,11,14),a.getEffectiveOn()); assertEquals("original",resolver.at(root,LocalDate.of(2026,10,14)).getId());
        assertEquals("original",resolver.at(root,LocalDate.of(2026,10,15)).getId());
        assertEquals("12400000",builder.map(revisionStore.get(child.getCurrentRevisionId()).getFinancialSnapshot()).get("amountValue"));
        service.propose("root","owner",p); assertEquals(1,proposals.size());
        at("2026-11-13T17:00:00Z");service.get("root","tenant");assertEquals("APPLIED",a.getStatus());
    }
    @Test void financialAppendixCannotApplyImmediately() {
        var p = new AmendmentPayload(UUID.randomUUID().toString(),"original",null,null,"Thay giá từ lúc ký phụ lục",
                Map.of("applyImmediately",true,"rentAmount","12000000"),List.of());
        assertThrows(AppException.class,() -> service.propose("root","owner",p)); assertTrue(proposals.isEmpty());
    }
    AmendmentPayload replace(ContractAmendment previous,Map<String,Object> changes) {
        return new AmendmentPayload(UUID.randomUUID().toString(),"original",previous.getId(),previous.getEffectiveOn(),"Sửa lại phụ lục chưa áp dụng",changes,List.of());
    }
    @Test void scheduledAppendixOnlyBecomesSupersededAfterBothReplacementSignatures() {
        var old=request("owner");var oldChild=approve(old);signaturesReady(oldChild);oldChild.setStatus(ContractStatus.ACTIVE);service.signed(oldChild);
        assertTrue(service.get("root","owner").proposals().getFirst().canReplace());
        var payload=replace(old,Map.of("freeForm",true,"rentAmount","11000000"));
        var preview=service.preview("root","owner",payload);
        assertEquals("12000000",preview.comparison().stream().filter(r->r.field().equals("rentAmount")).findFirst().orElseThrow().before());
        service.propose("root","owner",payload);var next=proposals.getFirst();assertEquals("SCHEDULED",old.getStatus());
        assertFalse(service.get("root","owner").proposals().stream().filter(p->p.id().equals(old.getId())).findFirst().orElseThrow().canReplace());
        var child=approve(next);assertEquals("SCHEDULED",old.getStatus());
        assertEquals(oldChild.getCurrentRevisionId(),resolver.at(root,old.getEffectiveOn()).getId());
        signaturesReady(child);when(signatures.findFirstByContractIdAndSignerRoleOrderByCreatedAtDesc(child.getId(),SignerRole.TENANT)).thenReturn(Optional.empty());
        assertThrows(AppException.class,()->service.signed(child));assertEquals("SCHEDULED",old.getStatus());
        signaturesReady(child);service.signed(child);service.signed(child);
        assertEquals("SUPERSEDED",old.getStatus());assertEquals("SCHEDULED",next.getStatus());
        assertEquals(ContractStatus.ACTIVE,oldChild.getStatus());assertEquals(child.getCurrentRevisionId(),resolver.at(root,next.getEffectiveOn()).getId());
        at("2026-11-13T17:00:00Z");service.get("root","tenant");assertEquals("APPLIED",next.getStatus());assertEquals("SUPERSEDED",old.getStatus());
        assertEquals("original",root.getCurrentRevisionId());verify(documents,never()).delete(any());
    }
    @Test void replacementMayReturnAllValuesToOriginalAndItsWordNamesTheReplacedSignedDocument() throws Exception {
        var old=request("owner");var oldChild=approve(old);signaturesReady(oldChild);service.signed(oldChild);
        var p=replace(old,Map.of("freeForm",true));service.propose("root","owner",p);var next=proposals.getFirst();var child=approve(next);
        assertEquals("12400000",builder.map(revisionStore.get(child.getCurrentRevisionId()).getFinancialSnapshot()).get("amountValue"));
        var change=service.comparison(next).stream().filter(r->r.field().equals("rentAmount")).findFirst().orElseThrow();
        assertEquals("12000000",change.before());assertEquals("12400000",change.after());
        byte[] bytes=new ContractAmendmentRenderer(contracts,amendments,service,builder).render(child,revisionStore.get(child.getCurrentRevisionId()));
        try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes))){String text=String.join("\n",doc.getParagraphs().stream().map(p1->p1.getText()).toList());assertTrue(text.contains("VĂN BẢN THAY THẾ"));assertTrue(text.contains(oldChild.getContractNumber()));}
        signaturesReady(child);service.signed(child);assertEquals("SUPERSEDED",old.getStatus());
    }
    @Test void repeatedSignedReplacementsCompareLatestTermsAndCanReturnToBase() {
        var old=request("owner");var oldChild=approve(old);signaturesReady(oldChild);service.signed(oldChild);
        service.propose("root","owner",replace(old,Map.of("rentAmount","11000000")));
        var second=proposals.getFirst();var secondChild=approve(second);signaturesReady(secondChild);service.signed(secondChild);
        var revert=replace(second,Map.of("freeForm",true));
        var comparison=service.preview("root","owner",revert).comparison().stream().filter(r->r.field().equals("rentAmount")).findFirst().orElseThrow();
        assertEquals("11000000",comparison.before());assertEquals("12400000",comparison.after());
        service.propose("root","owner",revert);var third=proposals.getFirst();var thirdChild=approve(third);signaturesReady(thirdChild);service.signed(thirdChild);
        var again=replace(third,Map.of("rentAmount","11500000"));
        var latest=service.preview("root","owner",again).comparison().stream().filter(r->r.field().equals("rentAmount")).findFirst().orElseThrow();
        assertEquals("12400000",latest.before());assertEquals("11500000",latest.after());
        assertEquals("SUPERSEDED",old.getStatus());assertEquals("SUPERSEDED",second.getStatus());assertEquals("SCHEDULED",third.getStatus());
        assertEquals(thirdChild.getCurrentRevisionId(),resolver.at(root,third.getEffectiveOn()).getId());
    }
    @Test void failedReplacementLeavesOldScheduleAndRejectedVersionsCanBeRetried() {
        var old=request("owner");var oldChild=approve(old);signaturesReady(oldChild);service.signed(oldChild);
        service.propose("root","owner",replace(old,Map.of("rentAmount","11000000")));var denied=proposals.getFirst();
        service.decide("root",denied.getId(),"tenant",new AmendmentDecision("REJECT","Không đồng ý bản sửa này"));assertEquals("SCHEDULED",old.getStatus());
        service.propose("root","owner",replace(old,Map.of("rentAmount","11500000")));var editing=proposals.getFirst();
        service.decide("root",editing.getId(),"tenant",new AmendmentDecision("REQUEST_CHANGES","Cần sửa lại đúng giá đã nói"));
        service.propose("root","owner",replace(editing,Map.of("rentAmount","11600000")));var next=proposals.getFirst();var child=approve(next);
        assertEquals("SCHEDULED",old.getStatus());signaturesReady(child);service.signed(child);
        assertEquals("SUPERSEDED",old.getStatus());assertEquals("SUPERSEDED",editing.getStatus());assertEquals("REJECTED",denied.getStatus());
    }
    @Test void expiredOrWithdrawnReplacementNeverDiscardsTheOldSignedAgreement() {
        var old=request("owner");var oldChild=approve(old);signaturesReady(oldChild);service.signed(oldChild);
        service.propose("root","owner",replace(old,Map.of("rentAmount","11000000")));var next=proposals.getFirst();
        service.decide("root",next.getId(),"owner",new AmendmentDecision("WITHDRAW",null));assertEquals("SCHEDULED",old.getStatus());
        service.propose("root","owner",replace(old,Map.of("rentAmount","11500000")));var expiring=proposals.getFirst();expiring.setRespondBy(time.now().minusSeconds(1));
        service.get("root","owner");assertEquals("EXPIRED",expiring.getStatus());assertEquals("SCHEDULED",old.getStatus());
        at("2026-11-13T17:00:00Z");service.get("root","tenant");assertEquals("APPLIED",old.getStatus());
    }
    @Test void replacementLocksDateAndStopsAtEffectiveMidnightOrEarlierIssuedInvoice() {
        var old=request("owner");var child=approve(old);signaturesReady(child);service.signed(child);
        var p=replace(old,Map.of("rentAmount","11000000"));
        assertThrows(AppException.class,()->service.propose("root","owner",new AmendmentPayload(p.requestId(),p.baseRevisionId(),p.previousProposalId(),LocalDate.of(2026,12,14),p.reason(),p.changes(),List.of())));
        at("2026-11-13T16:59:59Z");assertTrue(service.get("root","owner").availableDates().contains(old.getEffectiveOn()));
        service.preview("root","owner",p);
        bills.add(MonthlyInvoice.builder().periodStart(LocalDate.of(2026,10,14)).periodEndExclusive(old.getEffectiveOn()).status(MonthlyInvoiceStatus.UNPAID).build());
        assertThrows(AppException.class,()->service.preview("root","owner",p));bills.clear();
        at("2026-11-13T17:00:00Z");assertThrows(AppException.class,()->service.propose("root","owner",p));
    }
    @Test void revertingScheduledVehicleChangeRebuildsFutureParkingBackToBaseCounts() {
        service.propose("root","owner",payload(Map.of("motorbikeCount",1)));var old=proposals.getFirst();var oldChild=approve(old);signaturesReady(oldChild);service.signed(oldChild);
        service.propose("root","owner",replace(old,Map.of("freeForm",true)));var next=proposals.getFirst();var child=approve(next);signaturesReady(child);service.signed(child);
        verify(parking).activateAmendmentHold(eq("root"),eq(child.getId()),eq("rental"),eq("listing"),eq(old.getEffectiveOn()),eq(LocalDate.of(2027,1,14)),eq(0),eq(0));
    }
    @Test void revertingScheduledLeaseChangeRestoresFutureLengthAndNeverTouchesSignedRoot() {
        original.setLeaseSnapshot("{\"startDateText\":\"14/10/2026\",\"endDateText\":\"13/01/2027\",\"durationMonths\":3}");
        service.propose("root","owner",payload(Map.of("freeForm",true,"fields",Map.of("lease",Map.of("endDateText","13/02/2027","durationMonths",4)))));
        var old=proposals.getFirst();var oldChild=approve(old);signaturesReady(oldChild);service.signed(oldChild);assertEquals(4,root.getEffectiveLeaseMonths());
        service.propose("root","owner",replace(old,Map.of("freeForm",true)));var next=proposals.getFirst();var child=approve(next);signaturesReady(child);service.signed(child);
        assertEquals(3,root.getEffectiveLeaseMonths());assertEquals("original",root.getCurrentRevisionId());assertEquals("SUPERSEDED",old.getStatus());
    }
    @Test void twelveMonthLeaseUsesItsOwnMonthlyBoundaries() {
        when(rentals.findById("rental")).thenReturn(Optional.of(RentalRequest.builder().moveInDate(LocalDate.of(2026,10,14)).leaseMonths(12).build()));
        var available = service.get("root","tenant").availableDates(); assertEquals(11,available.size());
        assertEquals(LocalDate.of(2027,9,14),available.getLast());
        assertFalse(available.contains(LocalDate.of(2027,10,14)));
    }
    @Test void vehiclesHoldOnlyAfterApprovalAndActivateOnlyAfterVerifiedSigning() {
        var p = payload(Map.of("motorbikeCount",1)); service.propose("root","owner",p);
        verify(parking).validateAmendmentCapacity("root",null,"listing",p.effectiveOn(),LocalDate.of(2027,1,14),1,0);
        verify(parking,never()).holdForAmendment(any(),any(),any(),any(),any(),any(),anyInt(),anyInt(),any());
        var a = proposals.get(0); var child = approve(a);
        verify(parking).holdForAmendment(eq("root"),eq(child.getId()),eq("rental"),eq("listing"),eq(p.effectiveOn()),eq(LocalDate.of(2027,1,14)),eq(1),eq(0),any());
        verify(parking,never()).activateAmendmentHold(any(),any(),any(),any(),any(),any(),anyInt(),anyInt());
        signaturesReady(child); service.signed(child); service.signed(child);
        verify(parking,times(1)).activateAmendmentHold(eq("root"),eq(child.getId()),eq("rental"),eq("listing"),eq(p.effectiveOn()),eq(LocalDate.of(2027,1,14)),eq(1),eq(0));
        verify(parking,atLeastOnce()).validateAmendmentCapacity("root",child.getId(),"listing",p.effectiveOn(),LocalDate.of(2027,1,14),1,0);
    }
    @Test void parkingCapacityIsCheckedBeforePreviewSubmissionApprovalAndSigning() {
        var p=payload(Map.of("freeForm",true,"motorbikeCount",3,"carCount",1));
        doThrow(new AppException(com.hs.listing.advice.ListingErrorCode.PARKING_CAPACITY_CHANGED,"Không đủ chỗ"))
                .when(parking).validateAmendmentCapacity(any(),any(),any(),any(),any(),anyInt(),anyInt());
        assertThrows(AppException.class,()->service.preview("root","owner",p));
        assertThrows(AppException.class,()->service.propose("root","owner",p));
        assertTrue(proposals.isEmpty()); assertEquals(1,contractStore.size());
        doNothing().when(parking).validateAmendmentCapacity(any(),any(),any(),any(),any(),anyInt(),anyInt());
        service.propose("root","owner",p);var a=proposals.getFirst();
        doThrow(new AppException(com.hs.listing.advice.ListingErrorCode.PARKING_CAPACITY_CHANGED,"Không đủ chỗ"))
                .when(parking).validateAmendmentCapacity(any(),any(),any(),any(),any(),anyInt(),anyInt());
        assertThrows(AppException.class,()->approve(a));assertEquals("REQUESTED",a.getStatus());assertEquals(1,contractStore.size());
        doNothing().when(parking).validateAmendmentCapacity(any(),any(),any(),any(),any(),anyInt(),anyInt());
        var child=approve(a); signaturesReady(child);
        doThrow(new AppException(com.hs.listing.advice.ListingErrorCode.PARKING_CAPACITY_CHANGED,"Không đủ chỗ"))
                .when(parking).validateAmendmentCapacity(any(),eq(child.getId()),any(),any(),any(),anyInt(),anyInt());
        assertThrows(AppException.class,()->service.validateSigning(child));
        assertThrows(AppException.class,()->service.signed(child));assertEquals("APPROVED",a.getStatus());
        verify(parking,never()).activateAmendmentHold(any(),any(),any(),any(),any(),any(),anyInt(),anyInt());
    }
    @Test void standaloneListingAboveOriginalLimitsCanBeApprovedAndSignedWithRealParkingService() {
        var bookings=mock(com.hs.listing.repository.ParkingReservationRepository.class);
        var listings=mock(com.hs.listing.repository.ListingRepository.class);
        var branches=mock(com.hs.listing.repository.PropertyBranchRepository.class);
        var listing=com.hs.listing.model.Listing.builder().id("listing").maxMotorbikeCount(0).maxCarCount(0).build();
        when(listings.findById("listing")).thenReturn(Optional.of(listing));when(listings.findByIdForUpdate("listing")).thenReturn(Optional.of(listing));
        List<com.hs.listing.model.ParkingReservation> store=new ArrayList<>();
        when(bookings.findByContractId(anyString())).thenAnswer(i->store.stream().filter(r->Objects.equals(r.getContractId(),i.getArgument(0))).toList());
        when(bookings.save(any())).thenAnswer(i->{com.hs.listing.model.ParkingReservation r=i.getArgument(0);if(!store.contains(r))store.add(r);return r;});
        ReflectionTestUtils.setField(service,"parking",new ParkingReservationService(bookings,branches,listings));
        original.setPropertySnapshot("{\"maxVehicles\":0,\"maxMotorbikeCount\":0,\"maxCarCount\":0}");
        var p=payload(Map.of("freeForm",true,"motorbikeCount",5,"carCount",2));
        assertEquals(2,service.preview("root","owner",p).comparison().size());
        service.propose("root","owner",p);assertTrue(store.isEmpty());var a=proposals.getFirst();var child=approve(a);
        assertEquals("APPROVED",a.getStatus());assertEquals(2,store.size());
        assertThrows(AppException.class,()->service.signed(child));assertEquals("original",root.getCurrentRevisionId());
        signaturesReady(child);service.signed(child);
        assertEquals("SCHEDULED",a.getStatus());
        var tenant=builder.map(revisionStore.get(child.getCurrentRevisionId()).getTenantSnapshot());
        assertEquals(5,tenant.get("motorbikeCount"));assertEquals(2,tenant.get("carCount"));
        assertEquals(7,store.stream().filter(r->r.getStatus()==com.hs.listing.model.constant.ParkingReservationStatus.ACTIVE).mapToInt(com.hs.listing.model.ParkingReservation::getQuantity).sum());
        assertEquals(0,listing.getMaxMotorbikeCount());assertEquals(0,listing.getMaxCarCount());verifyNoInteractions(branches);
        verify(bookings,never()).findOverlappingListingReservations(any(),any(),any(),any(),any());
    }
    @Test void branchLinkedAmendmentAboveListingLimitsChecksActualPoolThroughoutConsentAndSigning() {
        var bookings=mock(com.hs.listing.repository.ParkingReservationRepository.class);
        var listings=mock(com.hs.listing.repository.ListingRepository.class);
        var branches=mock(com.hs.listing.repository.PropertyBranchRepository.class);
        var listing=com.hs.listing.model.Listing.builder().id("listing").branchId("branch").maxMotorbikeCount(1).maxCarCount(0).build();
        var branch=com.hs.listing.model.PropertyBranch.builder().id("branch").motorbikeParkingCapacity(10).carParkingCapacity(0).build();
        when(listings.findById("listing")).thenReturn(Optional.of(listing));when(listings.findByIdForUpdate("listing")).thenReturn(Optional.of(listing));
        when(branches.findByIdForUpdate("branch")).thenReturn(Optional.of(branch));
        var effective=LocalDate.of(2026,11,14);var end=LocalDate.of(2027,1,14);
        var old=com.hs.listing.model.ParkingReservation.builder().contractId("root").branchId("branch").listingId("listing")
                .vehicleType(com.hs.listing.model.constant.VehicleType.MOTORBIKE).quantity(2).startDate(effective.minusMonths(1)).endDateExclusive(end)
                .status(com.hs.listing.model.constant.ParkingReservationStatus.ACTIVE).build();
        var other=com.hs.listing.model.ParkingReservation.builder().contractId("other").branchId("branch").listingId("other-listing")
                .vehicleType(com.hs.listing.model.constant.VehicleType.MOTORBIKE).quantity(6).startDate(effective).endDateExclusive(end)
                .status(com.hs.listing.model.constant.ParkingReservationStatus.ACTIVE).build();
        List<com.hs.listing.model.ParkingReservation> store=new ArrayList<>(List.of(old,other));
        when(bookings.findByContractId(anyString())).thenAnswer(i->store.stream().filter(r->Objects.equals(r.getContractId(),i.getArgument(0))).toList());
        when(bookings.findOverlappingBranchReservations(any(),any(),any(),any(),any())).thenAnswer(i->store.stream()
                .filter(r->r.getVehicleType()==i.getArgument(1) && (r.getStatus()==com.hs.listing.model.constant.ParkingReservationStatus.ACTIVE || r.getStatus()==com.hs.listing.model.constant.ParkingReservationStatus.HELD)
                        && r.getStartDate().isBefore(i.getArgument(4)) && r.getEndDateExclusive().isAfter(i.getArgument(3))).toList());
        when(bookings.save(any())).thenAnswer(i->{com.hs.listing.model.ParkingReservation r=i.getArgument(0);if(!store.contains(r))store.add(r);return r;});
        ReflectionTestUtils.setField(service,"parking",new ParkingReservationService(bookings,branches,listings));
        original.setPropertySnapshot("{\"maxVehicles\":2,\"maxMotorbikeCount\":1,\"maxCarCount\":0}");
        var tenant=builder.map(original.getTenantSnapshot());tenant.put("motorbikeCount",2);original.setTenantSnapshot(builder.write(tenant));
        original.setChargesSnapshot("[{\"name\":\"Xe máy\",\"chargeType\":\"MOTORBIKE_PARKING\",\"billingMethod\":\"PER_VEHICLE_MONTH\",\"unitAmount\":\"50000\",\"estimatedMonthlyAmount\":\"100000\",\"includedInRent\":false}]");
        var tooMany=payload(Map.of("freeForm",true,"motorbikeCount",5));
        assertThrows(AppException.class,()->service.preview("root","owner",tooMany));
        assertThrows(AppException.class,()->service.propose("root","owner",tooMany));assertTrue(proposals.isEmpty());
        service.propose("root","owner",payload(Map.of("freeForm",true,"motorbikeCount",4)));var a=proposals.getFirst();var child=approve(a);
        var held=store.getLast();assertEquals(2,held.getQuantity());assertEquals(com.hs.listing.model.constant.ParkingReservationStatus.HELD,held.getStatus());
        service.validateSigning(child);signaturesReady(child);service.signed(child);
        assertEquals("SCHEDULED",a.getStatus());assertEquals(effective,old.getEndDateExclusive());assertEquals(6,other.getQuantity());
        var revision=revisionStore.get(child.getCurrentRevisionId());assertEquals(4,builder.map(revision.getTenantSnapshot()).get("motorbikeCount"));
        assertEquals("200000",builder.rows(revision.getChargesSnapshot()).getFirst().get("estimatedMonthlyAmount"));
        assertEquals(original.getInitialPaymentSnapshot(),revision.getInitialPaymentSnapshot());
        assertEquals(10,store.stream().filter(r->r.getStatus()==com.hs.listing.model.constant.ParkingReservationStatus.ACTIVE && r.getEndDateExclusive().isAfter(effective)).mapToInt(com.hs.listing.model.ParkingReservation::getQuantity).sum());
        assertEquals(1,listing.getMaxMotorbikeCount());assertEquals(10,branch.getMotorbikeParkingCapacity());
    }
    @Test void requestingChangesAfterFirstSignaturePreservesPdfAndCancelsSecondRequest() {
        var a = request("owner"); var child = approve(a);
        var first = SignatureRequest.builder().status(SignatureRequestStatus.SIGNED).build();
        var second = SignatureRequest.builder().status(SignatureRequestStatus.EMBEDDING).build();
        when(signatures.findByContractIdOrderByCreatedAtDesc(child.getId())).thenReturn(List.of(first,second));
        service.decide("root",a.getId(),"tenant",new AmendmentDecision("REQUEST_CHANGES","Cần sửa đơn giá trước khi ký"));
        assertEquals("CHANGES_REQUESTED",a.getStatus()); assertEquals(ContractStatus.CANCELLED,child.getStatus());
        assertEquals(SignatureRequestStatus.SIGNED,first.getStatus()); assertEquals(SignatureRequestStatus.CANCELLED,second.getStatus());
        assertThrows(AppException.class,() -> service.signed(child)); verify(documents,never()).delete(any());
        assertEquals("original",root.getCurrentRevisionId());
    }
    @Test void proposerCanWithdrawUnsignedAgreementButNotFullySignedAppendix() {
        var a = request("owner"); var child = approve(a);
        service.decide("root",a.getId(),"owner",new AmendmentDecision("WITHDRAW",null));
        assertEquals("WITHDRAWN",a.getStatus()); assertEquals(ContractStatus.CANCELLED,child.getStatus());
        var next = request("owner"); var signedChild = approve(next); signaturesReady(signedChild); service.signed(signedChild);
        assertThrows(AppException.class,() -> service.decide("root",next.getId(),"owner",new AmendmentDecision("WITHDRAW",null)));
        assertEquals("SCHEDULED",next.getStatus());
    }
    TenantAmendmentRequest wish() { return new TenantAmendmentRequest(UUID.randomUUID().toString(),"Tôi muốn giảm tiền phòng xuống 1 triệu",List.of()); }
    @Test void tenantCanOnlyRequestNegotiationNeverSubmitStructuredTerms() {
        assertThrows(AppException.class,() -> service.propose("root","tenant",payload(Map.of("rentAmount","1000000"))));
        assertThrows(AppException.class,() -> service.preview("root","tenant",payload(Map.of("rentAmount","1000000"))));
        assertThrows(AppException.class,() -> service.requestNegotiation("root","owner",wish()));
        service.requestNegotiation("root","tenant",wish()); var a = proposals.get(0);
        assertEquals("TENANT_REQUESTED",a.getStatus()); assertEquals("{}",a.getChangesSnapshot());
        assertNull(a.getAgreementContractId()); assertEquals("original",root.getCurrentRevisionId());
    }
    @Test void acceptedWishNeedsConcreteLandlordProposalAndTenantApprovalAgain() {
        var wish = wish(); service.requestNegotiation("root","tenant",wish);
        service.decideNegotiation("root",wish.requestId(),"owner",new AmendmentDecision("ACCEPT",null));
        var request = proposals.get(0); assertEquals("TENANT_ACCEPTED",request.getStatus()); assertNull(request.getAgreementContractId());
        var p = payload(Map.of("rentAmount","12000000"));
        var linked = new AmendmentPayload(p.requestId(),p.baseRevisionId(),null,p.effectiveOn(),p.reason(),p.changes(),List.of(),wish.requestId());
        service.propose("root","owner",linked); service.propose("root","owner",linked);
        assertEquals("TENANT_FULFILLED",request.getStatus()); var offer = proposals.get(0);
        assertEquals("REQUESTED",offer.getStatus()); assertEquals(wish.requestId(),offer.getTenantRequestId());
        assertThrows(AppException.class,() -> service.decide("root",offer.getId(),"owner",new AmendmentDecision("APPROVE",null)));
        var child = approve(offer); assertEquals(ContractStatus.DRAFT,child.getStatus());
        assertEquals("original",root.getCurrentRevisionId());
    }
    @Test void unansweredWishExpiresWithoutPretendingLandlordRejectedOrApprovingAnything() {
        var p = wish(); service.requestNegotiation("root","tenant",p); at("2026-10-16T03:00:00Z");
        service.get("root","owner"); var a = proposals.get(0); assertEquals("TENANT_EXPIRED",a.getStatus());
        assertNull(a.getAgreementContractId()); assertTrue(service.get("root","tenant").canPropose());
        assertThrows(AppException.class,() -> service.decideNegotiation("root",p.requestId(),"owner",new AmendmentDecision("ACCEPT",null)));
    }
    @Test void acceptedWishStillHasDraftingDeadlineAndCanBeExplicitlyRejected() {
        var p = wish(); service.requestNegotiation("root","tenant",p);
        service.decideNegotiation("root",p.requestId(),"owner",new AmendmentDecision("ACCEPT",null));
        at("2026-10-16T03:00:00Z"); service.get("root","owner"); assertEquals("TENANT_EXPIRED",proposals.get(0).getStatus());
        var another = wish(); service.requestNegotiation("root","tenant",another);
        service.decideNegotiation("root",another.requestId(),"owner",new AmendmentDecision("REJECT","Không thể giảm giá như mong muốn"));
        assertEquals("TENANT_REJECTED",proposals.get(0).getStatus());
    }
    @Test void wishRetriesAreIdempotentButDifferentTextOrSecondActiveWishAreDenied() {
        var p = wish(); service.requestNegotiation("root","tenant",p); service.requestNegotiation("root","tenant",p); assertEquals(1,proposals.size());
        assertThrows(AppException.class,() -> service.requestNegotiation("root","tenant",new TenantAmendmentRequest(p.requestId(),"Nội dung khác với yêu cầu trước",List.of())));
        assertThrows(AppException.class,() -> service.requestNegotiation("root","tenant",wish()));
        assertThrows(AppException.class,() -> service.decideNegotiation("root",p.requestId(),"tenant",new AmendmentDecision("ACCEPT",null)));
        assertThrows(AppException.class,() -> request("owner"));
    }
    @Test void landlordDraftIsPrivateAndNeverChangesBillingOrSignedTerms() {
        var p = payload(Map.of("rentAmount","11000000")); service.saveDraft("root","owner",p);
        assertEquals(p,service.getDraft("root","owner")); assertThrows(AppException.class,() -> service.getDraft("root","tenant"));
        assertThrows(AppException.class,() -> service.saveDraft("root","tenant",p));
        assertTrue(service.get("root","tenant").canPropose()); assertNull(service.get("root","tenant").draft());
        assertTrue(proposals.isEmpty()); assertEquals("original",resolver.at(root,LocalDate.of(2026,11,14)).getId());
    }
    @Test void counterAfterTenantWishKeepsSourceAndRetryVersionIsStable() {
        var wish = wish(); service.requestNegotiation("root","tenant",wish);
        service.decideNegotiation("root",wish.requestId(),"owner",new AmendmentDecision("ACCEPT",null));
        var p = payload(Map.of("rentAmount","12000000"));
        service.propose("root","owner",new AmendmentPayload(p.requestId(),p.baseRevisionId(),null,p.effectiveOn(),p.reason(),p.changes(),List.of(),wish.requestId()));
        var old = proposals.get(0); service.decide("root",old.getId(),"tenant",new AmendmentDecision("REQUEST_CHANGES","Xin giảm giá thêm một chút nữa"));
        var next = payload(Map.of("rentAmount","11500000"));
        var counter = new AmendmentPayload(next.requestId(),next.baseRevisionId(),old.getId(),next.effectiveOn(),next.reason(),next.changes(),List.of());
        service.propose("root","owner",counter); service.propose("root","owner",counter);
        assertEquals(wish.requestId(),proposals.get(0).getTenantRequestId()); assertEquals("SUPERSEDED",old.getStatus());
    }
}
