package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.engine.*;
import com.hs.listing.model.*;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractDraftIntegrityTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    final ContractRevisionRepository revisions = mock(ContractRevisionRepository.class);
    final SignatureRequestRepository signatures = mock(SignatureRequestRepository.class);
    final PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    final ContractDataBuilder builder = mock(ContractDataBuilder.class);
    final ContractDraftIntegrity policy = new ContractDraftIntegrity(mapper, rentals, revisions, signatures, payments, builder);
    final Contract c = Contract.builder().id("contract").rentalRequestId("rental").listingId("listing")
            .landlordId("owner").tenantId("tenant").status(ContractStatus.DRAFT).currentRevisionId("r1").build();
    final RentalRequest rental = RentalRequest.builder().id("rental").listing(Listing.builder().id("listing").build())
            .ownerId("owner").renterId("tenant").moveInDate(LocalDate.of(2026,11,3)).leaseMonths(3)
            .occupantCount(2).motorbikeCount(1).carCount(0).monthlyRentPrice(new BigDecimal("3000000"))
            .depositAmount(new BigDecimal("3000000")).build();
    ContractRevision original;
    @BeforeEach void setup() throws Exception {
        original = ContractRevision.builder().id("r1").contract(c).revisionNumber(1)
                .landlordSnapshot("{\"fullName\":\"Chủ nhà\",\"idNumber\":\"001234\",\"bankAccountNumber\":\"001000\"}")
                .tenantSnapshot("{\"fullName\":\"Người thuê\",\"occupantCount\":2,\"motorbikeCount\":1,\"carCount\":0}")
                .propertySnapshot("{\"fullAddress\":\"Địa chỉ đã chốt\",\"listingCode\":\"HS001\"}")
                .leaseSnapshot(mapper.writeValueAsString(ContractDataBuilder.leaseTerms(rental)))
                .financialSnapshot(mapper.writeValueAsString(Map.of("amountValue","3000000","amountNumber","3.000.000 VNĐ/tháng",
                        "amountWords",VietnameseCurrencyTextConverter.toWords(new BigDecimal("3000000")),
                        "depositAmountValue","3000000","depositAmountNumber","3.000.000 VNĐ",
                        "depositAmountWords",VietnameseCurrencyTextConverter.toWords(new BigDecimal("3000000")),
                        "paymentDueDay",MonthlyBillingSchedule.paymentDueDescription(),"paymentDueOffsetDays",4)))
                .chargesSnapshot("[]").equipmentSnapshot("[]").amenitiesSnapshot("[]")
                .initialPaymentSnapshot("{\"totalAmount\":\"3.000.000 VNĐ\"}")
                .policiesSnapshot("{\"petPolicy\":\"Không nuôi\",\"latePaymentFeeMode\":\"NONE\"}")
                .initialMetersSnapshot("{\"electricityInitial\":\"\",\"waterInitial\":\"Không áp dụng - nước tính theo 2 người\",\"electricMeterType\":\"Riêng\"}")
                .specialTerms("Thỏa thuận đã lưu").schemaVersion(3).build();
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        when(revisions.findByContractIdAndRevisionNumber("contract",1)).thenReturn(Optional.of(original));
    }
    @Test void currentSystemSnapshotIsReadyWithoutReadingLiveListingOrUserProfiles() {
        policy.validateForDocument(c,original);
        rental.getListing().setTitle("Listing đã sửa");
        policy.validateForDocument(c,original);
        verifyNoInteractions(builder);
    }
    @ParameterizedTest @ValueSource(strings={"startDateText","endDateText","durationMonths","durationText","handoverDateText"})
    void rejectsEveryCalendarOverride(String field) {
        var input = UpdateContractRevisionRequest.builder().lease(Map.of(field,field.equals("durationMonths")?12:"01/01/2030")).build();
        assertCode(6070,()->policy.prepareUpdate(c,original,input));
    }
    @ParameterizedTest @ValueSource(strings={"amountValue","amountNumber","amountWords","depositAmountValue","depositAmountNumber","depositAmountWords"})
    void rejectsNumericAndDisplayMoneyOverrides(String field) {
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().financial(Map.of(field,"9999999")).build()));
    }
    @ParameterizedTest @ValueSource(strings={"occupantCount","motorbikeCount","carCount"})
    void cannotChangePeopleOrParkingByEditingWord(String field) {
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().tenant(Map.of(field,5)).build()));
    }
    @Test void equivalentMoneyNumbersAreAcceptedButIdentityAndBankLeadingZerosAreSignificant() {
        assertNotNull(policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder()
                .financial(Map.of("amountValue",3000000,"depositAmountValue","3000000.00","amountNumber","3000000/tháng")).build()));
        for (String field : List.of("idNumber","bankAccountNumber"))
            assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().landlord(Map.of(field,"1000")).build()));
    }
    @Test void protectedNullUnknownNestedAndReceiptChangesAreRejected() {
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().landlord(Collections.singletonMap("fullName",null)).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().lease(Map.of("actorId","outsider")).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().property(Map.of("fullAddress",Map.of("value","spoof"))).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().initialPayment(Map.of("totalAmount","0")).build()));
    }
    @Test void fullFormRoundTripKeepsAllSourcesAndEditableFields() throws Exception {
        var full = policy.prepareUpdate(c,original,new UpdateContractRevisionRequest());
        full.setMeters(Map.of("electricityInitial","1250.125","waterInitial","Không áp dụng - nước tính theo 2 người","electricMeterType","Riêng"));
        full.setSpecialTerms("Nội dung hoàn thiện");
        var updated = policy.prepareUpdate(c,original,full);
        assertEquals("1250.125",updated.getMeters().get("electricityInitial"));
        assertEquals("Nội dung hoàn thiện",updated.getSpecialTerms());
        assertEquals(3,updated.getLease().get("durationMonths"));
        assertEquals("02/02/2027",updated.getLease().get("endDateText"));
        assertEquals(original.getFinancialSnapshot(),mapper.writeValueAsString(updated.getFinancial()));
        assertEquals("Thỏa thuận đã lưu",original.getSpecialTerms());
    }
    @Test void partialPatchPreservesUneditedTermsAndRejectsStaleEditor() {
        var updated = policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().baseRevisionId("r1").meters(Map.of("electricityInitial","0")).build());
        assertEquals("Thỏa thuận đã lưu",updated.getSpecialTerms());
        assertEquals("Không nuôi",updated.getPolicies().get("petPolicy"));
        assertCode(6072,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().baseRevisionId("r0").build()));
    }
    @ParameterizedTest @ValueSource(strings={"-1","NaN","Infinity","1e309","1.0001","abc"})
    void invalidReadingsNeverReachRevisionStorage(String value) {
        assertCode(6071,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().meters(Map.of("electricityInitial",value)).build()));
    }
    @Test void freezesEquipmentChargesAmenitiesPoliciesAndMeterType() {
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().charges(List.of(Map.of("amount",0))).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().equipments(List.of(Map.of("name","fake"))).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().amenities(List.of(Map.of("name","fake"))).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().policies(Map.of("petPolicy","Tùy ý")).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().meters(Map.of("electricMeterType","Chung")).build()));
        assertNotNull(policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().policies(Map.of("latePaymentFeeMode","FIXED_ONCE","latePaymentFeeAmount",50000)).build()));
    }
    @Test void corruptedOldRevisionCannotRenderButNewPartialSaveRestoresCanonicalTermsWithoutChangingLedger() throws Exception {
        var old = copy(); old.setLeaseSnapshot("{\"durationMonths\":12}");old.setFinancialSnapshot("{\"depositAmountValue\":\"1\"}");
        assertCode(6070,()->policy.validateForDocument(c,old));
        var fixed = policy.prepareUpdate(c,old,UpdateContractRevisionRequest.builder().specialTerms("Đã đối chiếu lại").build());
        assertEquals(3,fixed.getLease().get("durationMonths"));assertEquals("3000000",fixed.getFinancial().get("depositAmountValue"));
        verify(payments,never()).save(any());verify(rentals,never()).save(any());
    }
    @Test void approvedEarlierStartUsesCurrentRentalCalendarAndRetainsCapturedIdentity() throws Exception {
        rental.setMoveInDate(LocalDate.of(2026,11,1));
        var updated = policy.prepareUpdate(c,original,new UpdateContractRevisionRequest());
        assertEquals("01/11/2026",updated.getLease().get("startDateText"));
        assertEquals("31/01/2027",updated.getLease().get("endDateText"));
        assertEquals("001234",updated.getLandlord().get("idNumber"));
    }
    @ParameterizedTest @ValueSource(strings={"CREATED","PENDING_USER_CONFIRMATION","PROVIDER_SIGNED","EMBEDDING","SIGNED"})
    void cannotEditWhileSignatureExistsEvenIfContractStatusWasResetToDraft(String status) {
        when(signatures.findByContractIdOrderByCreatedAtDesc("contract")).thenReturn(List.of(SignatureRequest.builder().status(SignatureRequestStatus.valueOf(status)).build()));
        assertCode(6028,()->policy.prepareUpdate(c,original,new UpdateContractRevisionRequest()));
    }
    @Test void uncertainFailedSignatureBlocksEditingButDefinitivelyRejectedRequestAllowsIt() {
        var req=SignatureRequest.builder().status(SignatureRequestStatus.FAILED).failureCode("PROVIDER_STATE_UNKNOWN").build();
        when(signatures.findByContractIdOrderByCreatedAtDesc("contract")).thenReturn(List.of(req));
        assertCode(6028,()->policy.prepareUpdate(c,original,new UpdateContractRevisionRequest()));
        req.setFailureCode(null);req.setProviderSignatureValue("signature");
        assertCode(6028,()->policy.prepareUpdate(c,original,new UpdateContractRevisionRequest()));
        req.setProviderSignatureValue(null);req.setStatus(SignatureRequestStatus.REJECTED);
        assertNotNull(policy.prepareUpdate(c,original,new UpdateContractRevisionRequest()));
    }
    @Test void missingOriginalOrMismatchedRentalNeverFallsBackToClientData() {
        when(revisions.findByContractIdAndRevisionNumber("contract",1)).thenReturn(Optional.empty());
        assertCode(6071,()->policy.prepareUpdate(c,original,new UpdateContractRevisionRequest()));
        rental.setRenterId("outsider");
        assertCode(6071,()->policy.validateForDocument(c,original));
    }
    @Test void depositAndLinkedAgreementsDoNotUseRootLeaseCalendar() {
        c.setAgreementKind("DEPOSIT");policy.validateForDocument(c,original);
        c.setAgreementKind("RENEWAL");c.setParentContractId("parent");policy.validateForDocument(c,original);
        verifyNoInteractions(rentals,revisions,payments,signatures,builder);
    }
    @Test void bothSmartCaSigningRolesRecheckOldDataBeforeProceeding() throws Exception {
        var signing=mock(com.hs.contract.service.impl.SmartCaSignatureServiceImpl.class,CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(signing,"draftIntegrity",policy);
        when(revisions.findById("r1")).thenReturn(Optional.of(original));
        c.setPaymentStatus(ContractPaymentStatus.PAID);
        for (SignerRole role : SignerRole.values()) {
            c.setStatus(role==SignerRole.LANDLORD?ContractStatus.DRAFT:ContractStatus.TENANT_SIGNATURE_PENDING);
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(signing,"validateContractStatusForSigning",c,role);
            var corrupted=copy();corrupted.setLeaseSnapshot("{\"durationMonths\":12}");
            when(revisions.findById("r1")).thenReturn(Optional.of(corrupted));
            assertCode(6070,()->org.springframework.test.util.ReflectionTestUtils.invokeMethod(signing,"validateContractStatusForSigning",c,role));
            when(revisions.findById("r1")).thenReturn(Optional.of(original));
        }
    }
    @Test void zeroDepositAndSignedHistoryRemainSeparateFromDraftEditing() {
        rental.setDepositAmount(BigDecimal.ZERO);
        var result=policy.prepareUpdate(c,original,new UpdateContractRevisionRequest());
        assertEquals("0",result.getFinancial().get("depositAmountValue"));
        verify(payments,never()).save(any());
        c.setSignedAt(java.time.Instant.parse("2026-11-03T03:00:00Z"));
        assertCode(6028,()->policy.prepareUpdate(c,original,new UpdateContractRevisionRequest()));
    }
    @Test void optionalEmptyCapacityFieldsFromRealBuilderAreValidUnchangedSourceData() {
        original.setPropertySnapshot("{\"maxOccupants\":\"\",\"maxVehicles\":\"\",\"maxCarCount\":null}");
        policy.validateForDocument(c,original);
        assertNotNull(policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().property(Map.of("maxOccupants","","maxVehicles","")).build()));
        assertCode(6070,()->policy.prepareUpdate(c,original,UpdateContractRevisionRequest.builder().property(Map.of("maxOccupants",5)).build()));
    }
    ContractRevision copy() throws Exception { return mapper.readValue(mapper.writeValueAsString(original),ContractRevision.class); }
    void assertCode(int code, org.junit.jupiter.api.function.Executable action) { assertEquals(code,assertThrows(AppException.class,action).getCode()); }
}
