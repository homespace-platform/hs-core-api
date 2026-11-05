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
            .status(RentalRequestStatus.ACCEPTED).listingSnapshot("{\"minimumLeaseMonths\":6}")
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
        assertEquals(List.of("amountValue", "depositAmountValue"),
                preview.financialDifferences().stream().map(row -> row.field()).toList());
        assertFalse(preview.terms().containsKey("financial"));
        verifyNoInteractions(parking);
        verify(rentals, never()).save(any());

        var confirmed = sync.preview(contract, revision, preview.sourceVersion());
        sync.applyDraftTerm(contract, confirmed);
        assertEquals(3, rental.getLeaseMonths());
        assertEquals(new BigDecimal("12600000"), rental.getEstimatedLeaseTotal());
        assertEquals(new BigDecimal("3000000"), rental.getDepositAmount());
        assertEquals("{\"minimumLeaseMonths\":6}", rental.getListingSnapshot());
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
}
