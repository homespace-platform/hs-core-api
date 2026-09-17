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
