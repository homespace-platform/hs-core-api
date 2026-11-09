package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.Contract;
import com.hs.contract.model.ContractRevision;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.MonthlyInvoiceRepository;
import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.listing.dto.snapshot.ListingSnapshotDto;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.service.ParkingReservationService;
import com.hs.listing.service.RentalCostCalculator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractListingSyncTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final RentalRequestRepository rentals = mock(RentalRequestRepository.class);
    private final ContractDataBuilder data = mock(ContractDataBuilder.class);
    private final RentalCostCalculator costs = mock(RentalCostCalculator.class);
    private final ParkingReservationService parking = mock(ParkingReservationService.class);
    private final MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final BillingTime time = mock(BillingTime.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ContractListingSync sync = new ContractListingSync(listings, rentals, data,
            new AmendmentTermsBuilder(mapper), costs, parking, invoices, mapper, time);
    private final Listing listing = Listing.builder().id("listing").ownerId("owner").status(ListingStatus.RESERVED)
            .category(com.hs.listing.model.constant.ListingCategory.ROOM)
            .minimumLeaseMonths(3).approvedEditId("approved")
            .approvedContractSnapshot("{\"listingId\":\"listing\",\"category\":\"ROOM\",\"minimumLeaseMonths\":3,"
                    + "\"priceUnit\":\"MONTH\",\"depositType\":\"NONE\"}").build();
    private final RentalRequest rental = RentalRequest.builder().id("rental").listing(listing)
            .status(RentalRequestStatus.ACCEPTED).listingSnapshot("{\"listingId\":\"listing\",\"category\":\"ROOM\",\"minimumLeaseMonths\":6,\"charges\":[]}")
            .moveInDate(LocalDate.of(2027, 1, 31)).leaseMonths(6).depositAmount(new BigDecimal("3000000"))
            .estimatedMonthlyTotal(new BigDecimal("3200000")).build();
    private final Contract contract = Contract.builder().id("contract").listingId("listing").landlordId("owner")
            .rentalRequestId("rental").status(ContractStatus.DRAFT).build();
    private final ContractRevision revision = ContractRevision.builder().id("revision")
            .propertySnapshot("{\"fullAddress\":\"Địa chỉ cũ\",\"listingCode\":\"HS001\"}")
            .financialSnapshot("{\"amountValue\":\"3000000\",\"depositAmountValue\":\"3000000\"}")
            .build();

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(sync, "entityManager", entityManager);
        when(listings.findByIdAndActiveTrue("listing")).thenReturn(Optional.of(listing));
        when(rentals.findById("rental")).thenReturn(Optional.of(rental));
        when(data.listingTerms(any(ListingSnapshotDto.class), any(RentalRequest.class))).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("fullAddress", "Địa chỉ mới", "listingCode", "HS001"), List.of(), List.of(), List.of(), Map.of(), Map.of()));
        when(costs.calculateEffectiveMonthlyRent(any(Listing.class), anyInt())).thenReturn(new BigDecimal("3500000"));
        when(costs.calculateDeposit(any(Listing.class), eq(new BigDecimal("3500000")), eq(rental.getDepositAmount()))).thenReturn(BigDecimal.ZERO);
        when(time.today()).thenReturn(LocalDate.of(2027, 1, 30));
    }

    @Test void previewIsReadOnlyAndSavingShortensTheTermAndParkingAtMonthEnd() {
        var preview = sync.preview(contract, revision, null);
        assertEquals(3, preview.proposedMonths());
        assertEquals("29/04/2027", ((Map<?, ?>) preview.terms().get("lease")).get("endDateText"));
        assertEquals(6, rental.getLeaseMonths());
        assertEquals(List.of("financial.amountValue", "financial.depositAmountValue"),
                preview.financialDifferences().stream().map(row -> row.field()).toList());
        assertFalse(preview.terms().containsKey("financial"));
        verifyNoInteractions(parking);
        verify(rentals, never()).save(any());

        var confirmed = sync.preview(contract, revision, preview.sourceVersion());
        sync.applyDraftTerm(contract, confirmed);
        assertEquals(3, rental.getLeaseMonths());
        assertEquals(new BigDecimal("12600000"), rental.getEstimatedLeaseTotal());
        assertEquals(new BigDecimal("3000000"), rental.getDepositAmount());
        assertTrue(rental.getListingSnapshot().contains("\"minimumLeaseMonths\":6"));
        verify(entityManager).lock(listing, LockModeType.OPTIMISTIC);
        verify(parking).shortenHeldLease("rental", LocalDate.of(2027, 7, 31), LocalDate.of(2027, 4, 30));
    }

    @Test void longerChosenTermAndSignedLeaseAreNeverShortenedByListingReview() {
        rental.setLeaseMonths(12);
        assertNull(sync.preview(contract, revision, null).proposedMonths());
        rental.setLeaseMonths(6);
        contract.setStatus(ContractStatus.ACTIVE);
        listing.setStatus(ListingStatus.RENTED);
        assertNull(sync.preview(contract, revision, null).proposedMonths());
        verifyNoInteractions(parking);
    }

    @Test void directSourceSkipsComparisonsButKeepsApprovedDataAndTermSafety() {
        var source = sync.source(contract, revision, "approved");
        assertTrue(source.differences().isEmpty()); assertEquals(2,source.financialDifferences().size());
        assertEquals("Địa chỉ mới", ((Map<?, ?>)source.terms().get("property")).get("fullAddress"));
        assertEquals(3, source.proposedMonths()); assertEquals(6, rental.getLeaseMonths());
        verifyNoInteractions(parking); verify(rentals, never()).save(any());
        sync.applyDraftTerm(contract, source);
        assertEquals(3, rental.getLeaseMonths()); assertEquals(new BigDecimal("3000000"), rental.getDepositAmount());
        assertFalse(source.terms().containsKey("financial"));
    }

    @Test void directSourceStillRejectsStaleVersionsAndCapacityViolations() {
        assertThrows(AppException.class, () -> sync.source(contract, revision, "old"));
        revision.setTenantSnapshot("{\"occupantCount\":2}");
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("fullAddress","Địa chỉ mới","listingCode","HS001","maxOccupants",1),List.of(),List.of(),List.of(),Map.of(),Map.of()));
        assertThrows(AppException.class, () -> sync.source(contract, revision, "approved"));
        verifyNoInteractions(costs, parking); verify(rentals, never()).save(any());
    }

    @Test void blocksStaleApprovalAndPastNewEnd() {
        assertThrows(AppException.class, () -> sync.preview(contract, revision, "superseded"));
        when(time.today()).thenReturn(LocalDate.of(2027, 4, 30));
        assertThrows(AppException.class, () -> sync.preview(contract, revision, "approved"));
        verifyNoInteractions(parking);
    }
    @Test void renamedRoomAndChangedBranchAreStagedAndOnlyAppliedOnDraftSave() {
        rental.setLeaseMonths(12);listing.setBranchId("new-branch");contract.setBranchId("old-branch");
        revision.setPropertySnapshot("{\"unitNumber\":\"Phòng R101\",\"fullAddress\":\"Địa chỉ cũ\",\"listingCode\":\"HS001\"}");
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("unitNumber","Phòng R202","fullAddress","Địa chỉ mới","listingCode","HS001"),List.of(),List.of(),List.of(),Map.of(),Map.of()));
        var preview=sync.preview(contract,revision,null);
        assertEquals("old-branch",contract.getBranchId());assertEquals("Phòng R202",((Map<?,?>)preview.terms().get("property")).get("unitNumber"));
        assertTrue(preview.differences().stream().anyMatch(row->row.field().equals("property.unitNumber")));
        verifyNoInteractions(parking);
        sync.applyDraftTerm(contract,preview);
        verify(parking).replaceDraftHold(rental,0,0);assertEquals("new-branch",contract.getBranchId());
        assertEquals(12,rental.getLeaseMonths());assertEquals(new BigDecimal("3000000"),rental.getDepositAmount());
    }
    @Test void pendingListingEditsMustBeApprovedBeforeContractRefresh() {
        listing.setStatus(ListingStatus.PENDING_REVIEW); listing.setReviewOperationalStatus(ListingStatus.RESERVED);
        listing.setPendingEditId("new-edit");
        assertThrows(AppException.class,()->sync.preview(contract,revision,null));
        assertThrows(AppException.class,()->sync.source(contract,revision,null));
        verifyNoInteractions(data,parking); verify(rentals,never()).save(any());
    }

    @Test void sourceIsFrozenAndDoesNotReadUnapprovedLiveListingChanges() {
        listing.setMinimumLeaseMonths(1);
        assertEquals(3,sync.preview(contract,revision,null).proposedMonths());
        var captured=org.mockito.ArgumentCaptor.forClass(ListingSnapshotDto.class);
        verify(data).listingTerms(captured.capture(),any(RentalRequest.class));
        assertEquals(3,captured.getValue().getMinimumLeaseMonths());
        listing.setApprovedContractSnapshot("{}");
        assertThrows(AppException.class,()->sync.preview(contract,revision,null));
    }
    @Test void issuedInvoicesAndLowerCapacityBlockTheSyncWithoutWrites() {
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract"))
                .thenReturn(List.of(com.hs.contract.model.MonthlyInvoice.builder().build()));
        assertThrows(AppException.class,()->sync.preview(contract,revision,"approved"));
        when(invoices.findByContractIdOrderByPeriodIndexDesc("contract")).thenReturn(List.of());
        revision.setTenantSnapshot("{\"occupantCount\":2}");
        when(data.listingTerms(any(ListingSnapshotDto.class),any(RentalRequest.class))).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("fullAddress","Địa chỉ mới","listingCode","HS001","maxOccupants",1),List.of(),List.of(),List.of(),Map.of(),Map.of()));
        assertThrows(AppException.class,()->sync.preview(contract,revision,"approved"));
        verifyNoInteractions(parking);verify(rentals,never()).save(any());
    }
    @Test void listingPolicyCodesAndCurrentOccupancyAreSyncedWithoutChangingFinancialPolicy() {
        var before = Map.<String,Object>of("quietHoursPolicy","Sau 23 giờ","paymentCycle","Hàng tháng",
                "latePaymentFeeAmount",100000,"listingPolicies",List.of(Map.of("code","quietHoursPolicy")));
        var after = Map.<String,Object>of("quietHoursPolicy","Sau 22 giờ","paymentCycle","Hàng quý",
                "latePaymentFeeAmount",0,"listingPolicies",List.of(Map.of("code","quietHoursPolicy")));
        var merged=sync.mergeListingPolicies(before,Map.of("policies",after));
        assertEquals("Sau 22 giờ",merged.get("quietHoursPolicy"));
        assertEquals("Hàng tháng",merged.get("paymentCycle"));assertEquals(100000,merged.get("latePaymentFeeAmount"));
        revision.setTenantSnapshot("{\"occupantCount\":4,\"motorbikeCount\":2,\"carCount\":1}");
        sync.preview(contract,revision,null);
        var quote=org.mockito.ArgumentCaptor.forClass(RentalRequest.class);
        verify(data).listingTerms(any(),quote.capture());
        assertEquals(4,quote.getValue().getOccupantCount());assertEquals(2,quote.getValue().getMotorbikeCount());
        assertEquals(1,quote.getValue().getCarCount());assertNotSame(rental,quote.getValue());
        verify(costs).calculateEffectiveMonthlyRent(any(Listing.class),eq(4));
    }

    @Test void approvedUtilityRatesAreStagedForDraftAndAmendmentWithoutChangingReadingsOrOriginalTerms() throws Exception {
        var old=List.of(charge("Điện","ELECTRICITY","PER_KWH","2925"),charge("Nước","WATER","PER_M3","18750"));
        var approved=List.of(charge("Điện","ELECTRICITY","PER_KWH","4000"),charge("Nước","WATER","PER_M3","30000"));
        setListingCharges(old,approved);
        revision.setChargesSnapshot(mapper.writeValueAsString(old));
        revision.setInitialMetersSnapshot("{\"electricityInitial\":\"123\",\"waterInitial\":\"456\",\"electricMeterType\":\"Riêng\",\"waterMeterType\":\"Riêng\"}");
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("listingCode","HS001"),List.of(),List.of(),approved,Map.of(),
                Map.of("electricityInitial","","waterInitial","","electricMeterType","Riêng","waterMeterType","Riêng")));
        for(boolean draft:List.of(true,false)) {
            contract.setStatus(draft?ContractStatus.DRAFT:ContractStatus.ACTIVE);
            listing.setStatus(draft?ListingStatus.RESERVED:ListingStatus.RENTED);
            var result=draft?sync.source(contract,revision,"approved"):sync.preview(contract,revision,"approved");
            assertEquals(approved,result.terms().get("charges"));
            assertEquals("123",((Map<?,?>)result.terms().get("meters")).get("electricityInitial"));
            assertEquals("456",((Map<?,?>)result.terms().get("meters")).get("waterInitial"));
            assertEquals(2,result.differences().stream().filter(row->row.field().startsWith("charges.")).count());
        }
        assertEquals(old,new AmendmentTermsBuilder(mapper).rows(revision.getChargesSnapshot()));
        assertEquals(new BigDecimal("3000000"),rental.getDepositAmount());
    }

    @Test void utilityOnlyListingEditKeepsUnchangedQuotedFeesAndDoesNotExposeTheirLegacyNotes() throws Exception {
        var internet=charge("Internet / WiFi","INTERNET","PER_MONTH","0");
        internet.put("includedInRent",true);internet.put("note","Đã bao gồm trong giá thuê");
        internet.put("description","Khoản phí tham khảo mỗi tháng; xác nhận với chủ nhà khi xem.");
        var parking=charge("Phí gửi xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","90000");
        parking.put("note","90.000đ/xe × 0 xe");parking.put("estimatedMonthlyAmount","0");
        parking.put("description","Khoản phí tham khảo mỗi tháng; xác nhận với chủ nhà khi xem.");
        var electricity=charge("Tiền điện","ELECTRICITY","PER_KWH","2925");
        var water=charge("Tiền nước","WATER","PER_M3","18750");
        var current=List.of(internet,parking,electricity,water);
        revision.setChargesSnapshot(mapper.writeValueAsString(current));
        var newElectricity=charge("Tiền điện","ELECTRICITY","PER_KWH","4000");
        var newWater=charge("Tiền nước","WATER","PER_M3","30000");
        var approvedInternet=charge("Internet / WiFi","INTERNET","INCLUDED","0");
        approvedInternet.put("includedInRent",true);
        var approvedRows=List.of(approvedInternet,
                charge("Phí gửi xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","90000"),newElectricity,newWater);
        setListingCharges(current,approvedRows);
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("listingCode","HS001"),List.of(),List.of(),approvedRows,Map.of(),Map.of()));
        contract.setStatus(ContractStatus.ACTIVE);listing.setStatus(ListingStatus.RENTED);
        var result=sync.preview(contract,revision,"approved");
        assertEquals(List.of("Khoản phí · Tiền điện","Khoản phí · Tiền nước"),
                result.differences().stream().filter(d->d.field().startsWith("charges.")).map(d->d.label()).toList());
        var merged=(List<?>)result.terms().get("charges");
        assertEquals(internet,merged.get(0));assertEquals(parking,merged.get(1));
        assertEquals(newElectricity,merged.get(2));assertEquals(newWater,merged.get(3));
        assertEquals(current,new AmendmentTermsBuilder(mapper).rows(revision.getChargesSnapshot()));
    }

    @Test void realListingEditShowsWasteMethodAndMeterRatesButKeepsUnchangedFeesAndSignedLimits() throws Exception {
        var internet=charge("Internet / WiFi","INTERNET","PER_MONTH","0");
        internet.put("includedInRent",true);
        internet.put("description","Khoản phí tham khảo mỗi tháng; xác nhận với chủ nhà khi xem.");
        var parking=charge("Phí gửi xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","90000");
        parking.put("description","Khoản phí tham khảo mỗi tháng; xác nhận với chủ nhà khi xem.");
        var waste=charge("Phí dịch vụ & rác","SERVICE_OR_GARBAGE","PER_PERSON_MONTH","25000");
        var electricity=charge("Tiền điện","ELECTRICITY","PER_KWH","2925");
        var water=charge("Tiền nước","WATER","PER_M3","18750");
        var old=List.of(internet,parking,waste,electricity,water);
        revision.setChargesSnapshot(mapper.writeValueAsString(old));
        revision.setPropertySnapshot("{\"listingCode\":\"HS001\",\"maxVehicles\":1,\"maxMotorbikeCount\":1,\"maxCarCount\":0}");
        var included=charge("Internet / WiFi","INTERNET","INCLUDED","0");included.put("includedInRent",true);
        var car=charge("Phí gửi xe ô tô","CAR_PARKING","NOT_APPLICABLE","0");
        var approved=List.of(included,charge("Phí gửi xe máy","MOTORBIKE_PARKING","PER_VEHICLE_MONTH","90000"),
                charge("Phí dịch vụ, vệ sinh & rác","SERVICE_OR_GARBAGE","PER_MONTH","25000"),
                charge("Tiền điện","ELECTRICITY","PER_KWH","4000"),
                charge("Tiền nước","WATER","PER_M3","30000"),car);
        setListingCharges(old,approved);
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("listingCode","HS001","maxVehicles",1),List.of(),List.of(),approved,Map.of(),Map.of()));
        contract.setStatus(ContractStatus.ACTIVE);listing.setStatus(ListingStatus.RENTED);
        var result=sync.preview(contract,revision,"approved");
        assertEquals(List.of("Khoản phí · Phí dịch vụ, vệ sinh & rác","Khoản phí · Tiền điện","Khoản phí · Tiền nước"),
                result.differences().stream().filter(d->d.field().startsWith("charges.")).map(d->d.label()).toList());
        var merged=(List<Map<String,Object>>)result.terms().get("charges");
        assertEquals(5,merged.size());assertEquals(internet,merged.get(0));assertEquals(parking,merged.get(1));
        assertEquals("PER_MONTH",merged.get(2).get("billingMethod"));
        var property=(Map<?,?>)result.terms().get("property");
        assertEquals(1,property.get("maxMotorbikeCount"));assertEquals(0,property.get("maxCarCount"));
    }

    @Test void listingBillingMethodChangeRemainsVisibleAndIsNotTreatedAsAFormattingDifference() throws Exception {
        var old=charge("Phí dịch vụ & rác","SERVICE_OR_GARBAGE","PER_PERSON_MONTH","25000");
        old.put("amountAndMethod","25.000đ / người / tháng");
        old.put("billingMethodText","Người / tháng");
        var next=charge("Phí dịch vụ, vệ sinh & rác","SERVICE_OR_GARBAGE","PER_MONTH","25000");
        next.put("amountAndMethod","25.000đ / tháng");
        next.put("billingMethodText","Tháng");
        revision.setChargesSnapshot(mapper.writeValueAsString(List.of(old)));
        setListingCharges(List.of(old),List.of(next));
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("listingCode","HS001"),List.of(),List.of(),List.of(next),Map.of(),Map.of()));
        contract.setStatus(ContractStatus.ACTIVE);listing.setStatus(ListingStatus.RENTED);
        var result=sync.preview(contract,revision,"approved");
        var feeDiffs=result.differences().stream().filter(d->d.field().startsWith("charges.")).toList();
        assertEquals(1,feeDiffs.size());
        assertEquals("PER_MONTH",((List<Map<String,Object>>)result.terms().get("charges")).getFirst().get("billingMethod"));
        assertTrue(feeDiffs.getFirst().before().contains("Người / tháng"));
        assertTrue(feeDiffs.getFirst().after().contains("Tháng"));
        assertFalse(feeDiffs.getFirst().before().contains("PER_PERSON_MONTH"));
        assertFalse(feeDiffs.getFirst().after().contains("PER_MONTH"));
    }

    @Test void missingAcceptedSnapshotCannotSilentlyReplaceContractFees() throws Exception {
        revision.setChargesSnapshot(mapper.writeValueAsString(List.of(charge("Tiền điện","ELECTRICITY","PER_KWH","2925"))));
        rental.setListingSnapshot("{\"minimumLeaseMonths\":6}");
        assertThrows(AppException.class,()->sync.preview(contract,revision,"approved"));
        rental.setListingSnapshot("{\"listingId\":\"listing\",\"category\":\"ROOM\",\"minimumLeaseMonths\":6,\"charges\":[]}");
        assertThrows(AppException.class,()->sync.preview(contract,revision,"approved"));
        verifyNoInteractions(parking);
    }

    @Test void unspecifiedApprovedVehicleLimitsPreserveSignedLimits() {
        revision.setPropertySnapshot("{\"listingCode\":\"HS001\",\"maxVehicles\":1,\"maxMotorbikeCount\":1,\"maxCarCount\":0}");
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                new LinkedHashMap<>(Map.of("listingCode","HS001","maxVehicles",1)),List.of(),List.of(),List.of(),Map.of(),Map.of()));
        var result=sync.preview(contract,revision,"approved");
        var property=(Map<?,?>)result.terms().get("property");
        assertEquals(1,property.get("maxMotorbikeCount"));assertEquals(0,property.get("maxCarCount"));
        assertFalse(result.differences().stream().anyMatch(row->row.field().equals("property.maxMotorbikeCount")||row.field().equals("property.maxCarCount")));
    }

    @Test void approvedFeesCanBecomeFreeBeAddedOrRemovedAndAreaMatchesPerSquareMeterEstimate() throws Exception {
        revision.setChargesSnapshot(mapper.writeValueAsString(List.of(charge("Internet","INTERNET","PER_MONTH","100000"))));
        var free=charge("Internet","INTERNET","FREE","0");
        var areaCharge=new LinkedHashMap<>(charge("Dịch vụ","OTHER","PER_M2_MONTH","2000"));
        areaCharge.put("estimatedMonthlyAmount","60000");
        setListingCharges(List.of(charge("Internet","INTERNET","PER_MONTH","100000")),List.of(free,areaCharge));
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("listingCode","HS001","areaM2","30","areaText","30 m²"),List.of(),List.of(),List.of(free,areaCharge),Map.of(),Map.of()));
        var result=sync.source(contract,revision,"approved");
        assertEquals(List.of(free,areaCharge),result.terms().get("charges"));
        assertEquals("30",((Map<?,?>)result.terms().get("property")).get("areaM2"));
        setListingCharges(List.of(charge("Internet","INTERNET","PER_MONTH","100000")),List.of());
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(Map.of("listingCode","HS001"),List.of(),List.of(),List.of(),Map.of(),Map.of()));
        assertEquals(List.of(),sync.source(contract,revision,"approved").terms().get("charges"));
    }

    @Test void activeMeterChainChangesAreExplicitlyBlockedInsteadOfSilentlyPreservingOldListingValues() throws Exception {
        contract.setStatus(ContractStatus.ACTIVE);listing.setStatus(ListingStatus.RENTED);
        revision.setChargesSnapshot(mapper.writeValueAsString(List.of(charge("Nước","WATER","PER_M3","18750"))));
        setListingCharges(List.of(charge("Nước","WATER","PER_M3","18750")),List.of(charge("Nước","WATER","PER_PERSON_MONTH","30000")));
        when(data.listingTerms(any(),any())).thenReturn(new ContractDataBuilder.ListingTerms(Map.of("listingCode","HS001"),List.of(),List.of(),
                List.of(charge("Nước","WATER","PER_PERSON_MONTH","30000")),Map.of(),Map.of()));
        assertThrows(AppException.class,()->sync.preview(contract,revision,"approved"));
        verify(rentals,never()).save(any());
    }

    @Test void approvedStateWaterRateIsRejectedBeforeItCanBlockFutureInvoices() throws Exception {
        var stateWater = charge("Nước", "WATER", "STATE_WATER_RATE", "0");
        setListingCharges(List.of(), List.of(stateWater));
        when(data.listingTerms(any(), any())).thenReturn(new ContractDataBuilder.ListingTerms(
                Map.of("listingCode", "HS001"), List.of(), List.of(), List.of(stateWater), Map.of(), Map.of()));
        assertThrows(AppException.class, () -> sync.source(contract, revision, "approved"));
        contract.setStatus(ContractStatus.ACTIVE);
        listing.setStatus(ListingStatus.RENTED);
        assertThrows(AppException.class, () -> sync.preview(contract, revision, "approved"));
        verify(rentals, never()).save(any());
    }

    @Test void draftKeepsPaidRentButShowsEveryDifferenceWhileActiveFinancialSourceIsStageable() {
        var draft=sync.source(contract,revision,"approved");
        assertFalse(draft.terms().containsKey("financial"));
        assertEquals(2,draft.financialDifferences().size());
        contract.setStatus(ContractStatus.ACTIVE);listing.setStatus(ListingStatus.RENTED);
        var active=sync.preview(contract,revision,"approved");
        assertEquals("3500000",((Map<?,?>)active.terms().get("financial")).get("amountValue"));
        assertEquals("0",((Map<?,?>)active.terms().get("financial")).get("depositAmountValue"));
        assertTrue(active.financialDifferences().isEmpty());
        assertEquals(2,active.differences().stream().filter(row->row.field().startsWith("financial.")).count());
    }

    private static Map<String,Object> charge(String name,String type,String method,String rate) {
        var row=new LinkedHashMap<String,Object>();
        row.put("name",name);row.put("chargeType",type);row.put("billingMethod",method);row.put("unitAmount",rate);
        row.put("includedInRent",false);row.put("note","-");row.put("estimatedMonthlyAmount",null);
        row.put("amountAndMethod",rate+" / "+method);return row;
    }

    private void setListingCharges(List<Map<String,Object>> acceptedRows,List<Map<String,Object>> approvedRows) {
        try {
            var accepted=mapper.readValue(rental.getListingSnapshot(), ListingSnapshotDto.class);
            var approved=mapper.readValue(listing.getApprovedContractSnapshot(), ListingSnapshotDto.class);
            accepted.setCharges(snapshotCharges(acceptedRows));
            approved.setCharges(snapshotCharges(approvedRows));
            rental.setListingSnapshot(mapper.writeValueAsString(accepted));
            listing.setApprovedContractSnapshot(mapper.writeValueAsString(approved));
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private List<ListingSnapshotDto.ChargeItemSnapshot> snapshotCharges(List<Map<String,Object>> rows) {
        return rows.stream().map(row->ListingSnapshotDto.ChargeItemSnapshot.builder()
                .chargeType(Objects.toString(row.get("chargeType")))
                .billingMethod(Objects.toString(row.get("billingMethod")))
                .amount(new BigDecimal(Objects.toString(row.get("unitAmount"))))
                .includedInRent(Boolean.TRUE.equals(row.get("includedInRent")))
                .customName("OTHER".equals(row.get("chargeType"))?Objects.toString(row.get("name")):null)
                .description((String)row.get("description"))
                .build()).toList();
    }
}
