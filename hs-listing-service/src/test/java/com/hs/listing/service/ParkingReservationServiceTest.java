package com.hs.listing.service;

import com.hs.listing.model.Listing;
import com.hs.listing.model.ParkingReservation;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ParkingReservationStatus;
import com.hs.listing.model.constant.VehicleType;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ParkingReservationRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ParkingReservationServiceTest {

    private ParkingReservationRepository parkingReservationRepository;
    private PropertyBranchRepository propertyBranchRepository;
    private ListingRepository listingRepository;
    private ParkingReservationService service;

    @BeforeEach
    void setUp() {
        parkingReservationRepository = Mockito.mock(ParkingReservationRepository.class);
        propertyBranchRepository = Mockito.mock(PropertyBranchRepository.class);
        listingRepository = Mockito.mock(ListingRepository.class);
        service = new ParkingReservationService(parkingReservationRepository, propertyBranchRepository, listingRepository);
    }

    private final LocalDate amendmentStart = LocalDate.of(2026,11,15), amendmentEnd = LocalDate.of(2027,1,15);
    private List<ParkingReservation> amendmentBookings;
    private PropertyBranch amendmentBranch;
    private void amendmentPool(Integer motorbikeCapacity, Integer carCapacity) {
        var listing = Listing.builder().id("listing").branchId("branch").maxMotorbikeCount(0).maxCarCount(0).build();
        amendmentBranch = PropertyBranch.builder().id("branch").motorbikeParkingCapacity(motorbikeCapacity).carParkingCapacity(carCapacity).build();
        when(listingRepository.findById("listing")).thenReturn(java.util.Optional.of(listing));
        when(listingRepository.findByIdForUpdate("listing")).thenReturn(java.util.Optional.of(listing));
        when(propertyBranchRepository.findByIdForUpdate("branch")).thenReturn(java.util.Optional.of(amendmentBranch));
        amendmentBookings = new ArrayList<>();
        when(parkingReservationRepository.findByContractId(any())).thenAnswer(i -> amendmentBookings.stream()
                .filter(r -> java.util.Objects.equals(r.getContractId(),i.getArgument(0))).toList());
        when(parkingReservationRepository.findAllActiveBranchReservations(any(),any(),any())).thenAnswer(i -> amendmentBookings.stream()
                .filter(r -> "branch".equals(r.getBranchId()) && r.getVehicleType() == i.getArgument(1)
                        && (r.getStatus() == ParkingReservationStatus.ACTIVE || r.getStatus() == ParkingReservationStatus.HELD)).toList());
        when(parkingReservationRepository.findOverlappingBranchReservations(any(),any(),any(),any(),any())).thenAnswer(i -> amendmentBookings.stream()
                .filter(r -> "branch".equals(r.getBranchId()) && r.getVehicleType() == i.getArgument(1)
                        && (r.getStatus() == ParkingReservationStatus.ACTIVE || r.getStatus() == ParkingReservationStatus.HELD)
                        && r.getStartDate().isBefore(i.getArgument(4)) && r.getEndDateExclusive().isAfter(i.getArgument(3))).toList());
        when(parkingReservationRepository.save(any())).thenAnswer(i -> {
            ParkingReservation r = i.getArgument(0); if (!amendmentBookings.contains(r)) amendmentBookings.add(r); return r;
        });
    }
    private ParkingReservation booked(String contractId, VehicleType type, int count, LocalDate start, LocalDate end) {
        var booking = ParkingReservation.builder().contractId(contractId).branchId("branch").listingId("listing")
                .vehicleType(type).quantity(count).startDate(start).endDateExclusive(end).status(ParkingReservationStatus.ACTIVE).build();
        amendmentBookings.add(booking); return booking;
    }
    private void validateAmendment(int motorbikes, int cars) {
        service.validateAmendmentCapacity("root",null,"listing",amendmentStart,amendmentEnd,motorbikes,cars);
    }
    private void holdAmendment(int motorbikes, int cars) {
        service.holdForAmendment("root","appendix","rental","listing",amendmentStart,amendmentEnd,motorbikes,cars,Instant.now().plusSeconds(3600));
    }
    @Test void branchAmendmentIgnoresListingLimitsButCountsOtherRentalsAndOwnReplacementCorrectly() {
        amendmentPool(10,2);
        var old = booked("root",VehicleType.MOTORBIKE,2,amendmentStart.minusMonths(1),amendmentEnd);
        booked("other",VehicleType.MOTORBIKE,6,amendmentStart,amendmentEnd);
        booked("other-car",VehicleType.CAR,1,amendmentStart,amendmentEnd);
        validateAmendment(4,1); verify(parkingReservationRepository,never()).save(any());
        holdAmendment(4,1);
        var held = amendmentBookings.stream().filter(r -> r.getStatus() == ParkingReservationStatus.HELD).toList();
        assertEquals(2,held.size());assertEquals(3,held.stream().mapToInt(ParkingReservation::getQuantity).sum());
        // Rechecking after approval must exclude THIS appendix's delta, not count it twice.
        service.validateAmendmentCapacity("root","appendix","listing",amendmentStart,amendmentEnd,4,1);
        service.activateAmendmentHold("root","appendix","rental","listing",amendmentStart,amendmentEnd,4,1);
        assertEquals(amendmentStart,old.getEndDateExclusive());
        assertTrue(held.stream().allMatch(r -> r.getStatus() == ParkingReservationStatus.RELEASED));
        assertEquals(10,service.getMaxConcurrentUsage("branch",VehicleType.MOTORBIKE));
        verify(listingRepository,never()).save(any());verify(propertyBranchRepository,never()).save(any());
    }
    @Test void insufficientBranchCapacityRejectsBeforeAnyHoldOrOriginalMutation() {
        amendmentPool(10,0);
        var old = booked("root",VehicleType.MOTORBIKE,2,amendmentStart.minusMonths(1),amendmentEnd);
        booked("other",VehicleType.MOTORBIKE,6,amendmentStart,amendmentEnd);
        var error = assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(5,0));
        assertTrue(error.getMessage().contains("4 chỗ xe máy"));
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> holdAmendment(4,1));
        verify(parkingReservationRepository,never()).save(any());assertEquals(amendmentEnd,old.getEndDateExclusive());
    }
    @Test void branchHeldSlotsFromAnotherAgreementAlsoConsumeCapacity() {
        amendmentPool(5,0);
        booked("other-pending",VehicleType.MOTORBIKE,3,amendmentStart,amendmentEnd).setStatus(ParkingReservationStatus.HELD);
        validateAmendment(2,0);
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(3,0));
    }
    @Test void futureLeaseExtensionReservesFullCountsAfterOldRootBookingEnds() {
        amendmentPool(5,0);
        var midpoint = amendmentStart.plusMonths(1);
        booked("root",VehicleType.MOTORBIKE,2,amendmentStart.minusMonths(1),midpoint);
        holdAmendment(3,0);
        var held = amendmentBookings.stream().filter(r -> r.getStatus() == ParkingReservationStatus.HELD).toList();
        assertEquals(2,held.size());
        assertEquals(1,held.get(0).getQuantity());assertEquals(amendmentStart,held.get(0).getStartDate());assertEquals(midpoint,held.get(0).getEndDateExclusive());
        assertEquals(3,held.get(1).getQuantity());assertEquals(midpoint,held.get(1).getStartDate());assertEquals(amendmentEnd,held.get(1).getEndDateExclusive());
        assertEquals(3,service.calculatePeakUsage(amendmentBookings,amendmentStart,amendmentEnd));
    }
    @Test void extensionCannotBorrowRootSlotsAfterTheyHaveEnded() {
        amendmentPool(5,0);
        var midpoint = amendmentStart.plusMonths(1);
        booked("root",VehicleType.MOTORBIKE,2,amendmentStart.minusMonths(1),midpoint);
        booked("other",VehicleType.MOTORBIKE,4,midpoint,amendmentEnd);
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> holdAmendment(2,0));
        verify(parkingReservationRepository,never()).save(any());
    }
    @Test void nonOverlappingBranchBookingsUsePeakNotSumAndRespectExclusiveEnd() {
        amendmentPool(5,0);
        var midpoint = amendmentStart.plusMonths(1);
        booked("first",VehicleType.MOTORBIKE,3,amendmentStart,midpoint);
        booked("second",VehicleType.MOTORBIKE,3,midpoint,amendmentEnd);
        booked("outside",VehicleType.MOTORBIKE,100,amendmentEnd,amendmentEnd.plusMonths(1));
        validateAmendment(2,0);holdAmendment(2,0);
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(3,0));
    }
    @Test void replacementRetainsOldScheduledBookingUntilNewAppendixIsFullySigned() {
        amendmentPool(10,0);
        var historical = booked("root",VehicleType.MOTORBIKE,2,amendmentStart.minusMonths(1),amendmentStart);
        var scheduled = booked("root",VehicleType.MOTORBIKE,4,amendmentStart,amendmentEnd);
        booked("other",VehicleType.MOTORBIKE,5,amendmentStart,amendmentEnd);
        holdAmendment(5,0);
        assertEquals(ParkingReservationStatus.ACTIVE,scheduled.getStatus());assertEquals(4,scheduled.getQuantity());
        service.activateAmendmentHold("root","appendix","rental","listing",amendmentStart,amendmentEnd,5,0);
        assertEquals(ParkingReservationStatus.RELEASED,scheduled.getStatus());
        assertEquals(ParkingReservationStatus.ACTIVE,historical.getStatus());assertEquals(amendmentStart,historical.getEndDateExclusive());
        assertEquals(10,service.getMaxConcurrentUsage("branch",VehicleType.MOTORBIKE));
    }
    @Test void reductionAndCancellationNeverReleaseOriginalSlotsPrematurely() {
        amendmentPool(10,0);
        var original = booked("root",VehicleType.MOTORBIKE,5,amendmentStart.minusMonths(1),amendmentEnd);
        holdAmendment(2,0);verify(parkingReservationRepository,never()).save(any());
        holdAmendment(6,0);var delta = amendmentBookings.getLast();assertEquals(1,delta.getQuantity());
        service.releaseRenewalHold("appendix");assertEquals(ParkingReservationStatus.RELEASED,delta.getStatus());
        assertEquals(ParkingReservationStatus.ACTIVE,original.getStatus());assertEquals(amendmentEnd,original.getEndDateExclusive());
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> service.activateAmendmentHold("root","appendix","rental","listing",amendmentStart,amendmentEnd,6,0));
        assertEquals(amendmentEnd,original.getEndDateExclusive());
    }
    @Test void recheckBeforeActivationRejectsChangedCapacityWithoutReleasingAnySlots() {
        amendmentPool(5,0);
        var original = booked("root",VehicleType.MOTORBIKE,2,amendmentStart.minusMonths(1),amendmentEnd);
        holdAmendment(4,0);var held = amendmentBookings.getLast();
        amendmentBranch.setMotorbikeParkingCapacity(3);
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> service.activateAmendmentHold("root","appendix","rental","listing",amendmentStart,amendmentEnd,4,0));
        assertEquals(ParkingReservationStatus.HELD,held.getStatus());assertEquals(amendmentEnd,original.getEndDateExclusive());
    }
    @Test void zeroOrUnknownBranchCapacityAllowsOnlyZeroForThatVehicleType() {
        amendmentPool(null,0);validateAmendment(0,0);
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(1,0));
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(0,1));
    }
    @Test void malformedCountsDatesAndConcurrentBranchRelinkAreRejected() {
        amendmentPool(10,10);
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(-1,0));
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(0,101));
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> service.validateAmendmentCapacity("root",null,"listing",amendmentStart,amendmentStart,1,0));
        when(listingRepository.findByIdForUpdate("listing")).thenReturn(java.util.Optional.of(Listing.builder().id("listing").branchId("changed-branch").build()));
        assertThrows(com.hs.common.advice.entity.AppException.class,() -> validateAmendment(1,0));
        verify(parkingReservationRepository,never()).save(any());
    }

    @Test
    @DisplayName("6. Hai reservation không overlap có thể tái sử dụng slot")
    void testNonOverlappingReservationsReuseSlots() {
        // Res 1: 2026-01-01 -> 2026-06-01, qty = 2
        // Res 2: 2026-07-01 -> 2026-12-01, qty = 2
        // Capacity = 3.
        // Peak usage in requested period [2026-01-01, 2026-12-31) must be 2, NOT 4!
        List<ParkingReservation> reservations = List.of(
                ParkingReservation.builder()
                        .startDate(LocalDate.of(2026, 1, 1))
                        .endDateExclusive(LocalDate.of(2026, 6, 1))
                        .quantity(2)
                        .status(ParkingReservationStatus.ACTIVE)
                        .build(),
                ParkingReservation.builder()
                        .startDate(LocalDate.of(2026, 7, 1))
                        .endDateExclusive(LocalDate.of(2026, 12, 1))
                        .quantity(2)
                        .status(ParkingReservationStatus.ACTIVE)
                        .build()
        );

        int peak = service.calculatePeakUsage(
                reservations, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertEquals(2, peak, "Hai reservation không overlap chỉ chiếm tối đa 2 slot tại một thời điểm");
    }

    @Test void renewalHoldsOnlyAdditionalDatesAndConnectsThemToRootAfterSigning() {
        LocalDate start=LocalDate.of(2027,1,12),end=LocalDate.of(2027,4,12);
        ParkingReservation original=ParkingReservation.builder().contractId("root").listingId("listing").rentalRequestId("rental")
                .vehicleType(VehicleType.MOTORBIKE).quantity(2).status(ParkingReservationStatus.ACTIVE).endDateExclusive(start).build();
        when(parkingReservationRepository.findByContractId("root")).thenReturn(List.of(original));
        when(listingRepository.findByIdForUpdate("listing")).thenReturn(java.util.Optional.of(Listing.builder().id("listing").maxMotorbikeCount(2).build()));
        when(parkingReservationRepository.findOverlappingListingReservations(any(),any(),any(),any(),any())).thenReturn(List.of());
        Instant deadline=Instant.parse("2027-01-10T17:00:00Z");
        service.holdForRenewal("root","agreement",start,end,deadline);
        ArgumentCaptor<ParkingReservation> capture=ArgumentCaptor.forClass(ParkingReservation.class);
        verify(parkingReservationRepository).save(capture.capture());
        ParkingReservation held=capture.getValue();
        assertEquals("agreement",held.getContractId());assertEquals(start,held.getStartDate());assertEquals(end,held.getEndDateExclusive());
        assertEquals(2,held.getQuantity());assertEquals(ParkingReservationStatus.HELD,held.getStatus());assertEquals(deadline,held.getHoldExpiresAt());
        when(parkingReservationRepository.findByContractId("agreement")).thenReturn(List.of(held));
        service.activateRenewalHold("agreement","root");
        assertEquals("root",held.getContractId());assertEquals(ParkingReservationStatus.ACTIVE,held.getStatus());assertNull(held.getHoldExpiresAt());
        assertEquals(start,original.getEndDateExclusive());
    }

    @Test void amendmentReservesOnlyDeltaAndPreservesHistoricalParking() {
        var start = LocalDate.of(2026,11,14); var end = LocalDate.of(2027,1,14);
        var original = ParkingReservation.builder().contractId("root").listingId("listing").rentalRequestId("rental")
                .vehicleType(VehicleType.MOTORBIKE).quantity(1).startDate(start.minusMonths(1)).endDateExclusive(end).status(ParkingReservationStatus.ACTIVE).build();
        var listing = Listing.builder().id("listing").maxMotorbikeCount(3).maxCarCount(0).build();
        when(listingRepository.findById("listing")).thenReturn(java.util.Optional.of(listing));
        when(listingRepository.findByIdForUpdate("listing")).thenReturn(java.util.Optional.of(listing));
        when(parkingReservationRepository.findByContractId("root")).thenReturn(List.of(original));
        when(parkingReservationRepository.findOverlappingListingReservations(any(),any(),any(),any(),any())).thenReturn(List.of(original));
        service.holdForAmendment("root","appendix","rental","listing",start,end,2,0,Instant.now().plusSeconds(3600));
        var captured = ArgumentCaptor.forClass(ParkingReservation.class); verify(parkingReservationRepository).save(captured.capture());
        var hold = captured.getValue(); assertEquals(1,hold.getQuantity()); assertEquals(end,original.getEndDateExclusive());
        when(parkingReservationRepository.findByContractId("appendix")).thenReturn(List.of(hold));
        service.activateAmendmentHold("root","appendix","rental","listing",start,end,2,0);
        assertEquals(start,original.getEndDateExclusive()); assertEquals(ParkingReservationStatus.RELEASED,hold.getStatus());
        verify(parkingReservationRepository,times(4)).save(captured.capture());
        var adjusted = captured.getAllValues().getLast(); assertEquals(2,adjusted.getQuantity()); assertEquals(start,adjusted.getStartDate()); assertEquals("root",adjusted.getContractId());
    }

    @Test void conflictingParkingCapacityRejectsRenewalWithoutMutatingOriginalBooking() {
        LocalDate start=LocalDate.of(2027,1,12),end=LocalDate.of(2027,4,12);
        var original=ParkingReservation.builder().contractId("root").listingId("listing").vehicleType(VehicleType.MOTORBIKE)
                .quantity(2).status(ParkingReservationStatus.ACTIVE).endDateExclusive(start).build();
        var other=ParkingReservation.builder().quantity(1).startDate(start).endDateExclusive(end).build();
        when(parkingReservationRepository.findByContractId("root")).thenReturn(List.of(original));
        when(listingRepository.findByIdForUpdate("listing")).thenReturn(java.util.Optional.of(Listing.builder().id("listing").maxMotorbikeCount(2).build()));
        when(parkingReservationRepository.findOverlappingListingReservations(any(),any(),any(),any(),any())).thenReturn(List.of(other));
        assertThrows(com.hs.common.advice.entity.AppException.class,()->service.holdForRenewal("root","agreement",start,end,Instant.now()));
        verify(parkingReservationRepository,never()).save(any());assertEquals(ParkingReservationStatus.ACTIVE,original.getStatus());
    }

    @Test void expiredRenewalReleasesOnlyItsHeldSlotsNotOriginalActiveSlots() {
        var held=ParkingReservation.builder().contractId("agreement").status(ParkingReservationStatus.HELD).build();
        var active=ParkingReservation.builder().contractId("agreement").status(ParkingReservationStatus.ACTIVE).build();
        when(parkingReservationRepository.findByContractId("agreement")).thenReturn(List.of(held,active));
        service.releaseRenewalHold("agreement");
        assertEquals(ParkingReservationStatus.RELEASED,held.getStatus());assertEquals(ParkingReservationStatus.ACTIVE,active.getStatus());
        verify(parkingReservationRepository).save(held);verify(parkingReservationRepository,never()).findByContractId("root");
    }

    @Test
    @DisplayName("7. Hai reservation overlap phải cộng dồn peak usage")
    void testOverlappingReservationsAccumulateUsage() {
        // Res 1: 2026-01-01 -> 2026-07-01, qty = 2
        // Res 2: 2026-04-01 -> 2026-10-01, qty = 3
        // Peak usage must be 2 + 3 = 5
        List<ParkingReservation> reservations = List.of(
                ParkingReservation.builder()
                        .startDate(LocalDate.of(2026, 1, 1))
                        .endDateExclusive(LocalDate.of(2026, 7, 1))
                        .quantity(2)
                        .status(ParkingReservationStatus.ACTIVE)
                        .build(),
                ParkingReservation.builder()
                        .startDate(LocalDate.of(2026, 4, 1))
                        .endDateExclusive(LocalDate.of(2026, 10, 1))
                        .quantity(3)
                        .status(ParkingReservationStatus.ACTIVE)
                        .build()
        );

        int peak = service.calculatePeakUsage(
                reservations, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertEquals(5, peak, "Hai reservation overlap phải có peak usage là 5");
    }

    @Test
    @DisplayName("8. Boundary endDateExclusive không bị coi là overlap")
    void testBoundaryEndDateExclusiveDoesNotOverlap() {
        // Res 1: 2026-01-01 -> 2026-06-01, qty = 2
        // Res 2 starts exactly on 2026-06-01 -> 2026-12-01, qty = 2
        // At 2026-06-01, Res 1 has departed, Res 2 has arrived -> Peak must be 2, NOT 4!
        List<ParkingReservation> reservations = List.of(
                ParkingReservation.builder()
                        .startDate(LocalDate.of(2026, 1, 1))
                        .endDateExclusive(LocalDate.of(2026, 6, 1))
                        .quantity(2)
                        .status(ParkingReservationStatus.ACTIVE)
                        .build(),
                ParkingReservation.builder()
                        .startDate(LocalDate.of(2026, 6, 1))
                        .endDateExclusive(LocalDate.of(2026, 12, 1))
                        .quantity(2)
                        .status(ParkingReservationStatus.ACTIVE)
                        .build()
        );

        int peak = service.calculatePeakUsage(
                reservations, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));

        assertEquals(2, peak, "Ngày tiếp giáp endDateExclusive không được tính overlap");
    }

    @Test
    @DisplayName("10. ACCEPT tạo HELD reservation cho motorbike và car")
    void testAcceptCreatesHeldReservations() {
        RentalRequest request = RentalRequest.builder()
                .id("req-1")
                .moveInDate(LocalDate.of(2026, 10, 1))
                .leaseMonths(6)
                .motorbikeCount(2)
                .carCount(1)
                .build();

        Listing listing = Listing.builder()
                .id("list-1")
                .branchId("branch-1")
                .build();

        Instant holdExpiresAt = Instant.now().plusSeconds(900);

        when(parkingReservationRepository.save(any(ParkingReservation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<ParkingReservation> created = service.createHeldReservations(request, listing, holdExpiresAt);

        assertEquals(2, created.size());

        ParkingReservation mbRes = created.stream()
                .filter(r -> r.getVehicleType() == VehicleType.MOTORBIKE)
                .findFirst().orElseThrow();
        assertEquals(2, mbRes.getQuantity());
        assertEquals(ParkingReservationStatus.HELD, mbRes.getStatus());
        assertEquals(LocalDate.of(2026, 10, 1), mbRes.getStartDate());
        assertEquals(LocalDate.of(2027, 4, 1), mbRes.getEndDateExclusive());
        assertEquals(holdExpiresAt, mbRes.getHoldExpiresAt());

        ParkingReservation carRes = created.stream()
                .filter(r -> r.getVehicleType() == VehicleType.CAR)
                .findFirst().orElseThrow();
        assertEquals(1, carRes.getQuantity());
        assertEquals(ParkingReservationStatus.HELD, carRes.getStatus());
    }

    @Test
    @DisplayName("11. Expire/cancel giải phóng reservation")
    void testExpireAndCancelReleaseReservations() {
        ParkingReservation res = ParkingReservation.builder()
                .id("res-1")
                .rentalRequestId("req-1")
                .status(ParkingReservationStatus.HELD)
                .build();

        when(parkingReservationRepository.findByRentalRequestId("req-1"))
                .thenReturn(List.of(res));

        // Test cancel/release
        service.releaseReservationsForRequest("req-1");
        assertEquals(ParkingReservationStatus.RELEASED, res.getStatus());

        // Test expire
        res.setStatus(ParkingReservationStatus.HELD);
        service.expireReservationsForRequest("req-1");
        assertEquals(ParkingReservationStatus.EXPIRED, res.getStatus());
    }

    @Test
    @DisplayName("12. Contract ACTIVE chuyển reservation sang ACTIVE và liên kết contractId")
    void testContractActiveTransitionsReservationToActive() {
        ParkingReservation res = ParkingReservation.builder()
                .id("res-1")
                .rentalRequestId("req-1")
                .status(ParkingReservationStatus.HELD)
                .holdExpiresAt(Instant.now().plusSeconds(900))
                .build();

        when(parkingReservationRepository.findByRentalRequestId("req-1"))
                .thenReturn(List.of(res));

        service.activateReservationsForRequest("req-1", "contract-999");

        assertEquals(ParkingReservationStatus.ACTIVE, res.getStatus());
        assertEquals("contract-999", res.getContractId());
        assertNull(res.getHoldExpiresAt());
    }
}
