package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.CreateRentalRequest;
import com.hs.listing.dto.request.RejectRentalRequest;
import com.hs.listing.dto.response.RentalRequestResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.DepositType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.AddressRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RentalRequestServiceTest {

    private RentalRequestRepository rentalRequestRepository;
    private ListingRepository listingRepository;
    private ListingStatusService listingStatusService;
    private AddressRepository addressRepository;
    private StorageProperties storageProperties;
    private RentalCostCalculator rentalCostCalculator;
    private ParkingReservationService parkingReservationService;
    private PropertyBranchRepository propertyBranchRepository;
    private ObjectMapper objectMapper;
    private RentalRequestService rentalRequestService;

    @BeforeEach
    void setUp() {
        rentalRequestRepository = mock(RentalRequestRepository.class);
        listingRepository = mock(ListingRepository.class);
        listingStatusService = mock(ListingStatusService.class);
        addressRepository = mock(AddressRepository.class);
        storageProperties = mock(StorageProperties.class);
        parkingReservationService = mock(ParkingReservationService.class);
        when(parkingReservationService.getAvailability(any(), any(), any(), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(5, 0, 5));
        rentalCostCalculator = new RentalCostCalculator(parkingReservationService);
        propertyBranchRepository = mock(PropertyBranchRepository.class);
        objectMapper = new ObjectMapper();

        rentalRequestService = new RentalRequestService(
                rentalRequestRepository,
                listingRepository,
                listingStatusService,
                addressRepository,
                storageProperties,
                rentalCostCalculator,
                parkingReservationService,
                propertyBranchRepository,
                objectMapper,
                15
        );
    }

    @Test
    void createRentalRequest_rejectsUnauthenticated() {
        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, 0, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );
        assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest(null, null, req));
    }

    @Test
    void createRentalRequest_cannotRentOwnListing() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .status(ListingStatus.PUBLISHED)
                .priceAmount(BigDecimal.valueOf(5000000))
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, 0, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        AppException ex = assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest("user-1", "user1@gmail.com", req));
        assertEquals(ListingErrorCode.CANNOT_RENT_OWN_LISTING.getCode(), ex.getCode());
    }

    @Test
    void createRentalRequest_cannotRentReservedListing() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .status(ListingStatus.RESERVED)
                .priceAmount(BigDecimal.valueOf(5000000))
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, 0, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        AppException ex = assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest("renter-1", "r@gmail.com", req));
        assertEquals(ListingErrorCode.LISTING_ALREADY_RESERVED.getCode(), ex.getCode());
    }

    @Test
    void createRentalRequest_success_pendingDoesNotHoldSlot() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .title("Can ho 2PN")
                .status(ListingStatus.PUBLISHED)
                .depositType(DepositType.FIXED_AMOUNT)
                .priceAmount(BigDecimal.valueOf(5000000))
                .depositAmount(BigDecimal.valueOf(5000000))
                .maxMotorbikeCount(2)
                .maxCarCount(1)
                .charges(List.of())
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        when(rentalRequestRepository.findFirstByListingIdAndRenterIdAndStatusIn(anyString(), anyString(), anyCollection()))
                .thenReturn(Optional.empty());
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parkingReservationService.getAvailability(any(), any(), any(), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(2, 0, 2));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 2, 1, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        RentalRequestResponse res = rentalRequestService.createRentalRequest("renter-1", "a@gmail.com", req);

        assertNotNull(res);
        assertEquals("listing-1", res.listingId());
        assertEquals("renter-1", res.renterId());
        assertEquals(RentalRequestStatus.PENDING, res.status());
        assertEquals(2, res.occupantCount());
        assertEquals(1, res.motorbikeCount());
        assertEquals(0, res.carCount());
        assertNotNull(res.costBreakdownSnapshot());
        verify(rentalRequestRepository, times(1)).save(any(RentalRequest.class));
        // Verify PENDING request NEVER creates reservations (slot is NOT held)
        verify(parkingReservationService, never()).createHeldReservations(any(), any(), any());
    }

    @Test
    void acceptRentalRequest_marksReservedAndCancelsOtherPending_createsHeldReservation() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .title("Can ho 2PN")
                .status(ListingStatus.PUBLISHED)
                .build();

        RentalRequest req1 = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .ownerId("owner-1")
                .renterId("renter-1")
                .status(RentalRequestStatus.PENDING)
                .moveInDate(LocalDate.now().plusDays(1))
                .leaseMonths(6)
                .motorbikeCount(2)
                .carCount(1)
                .build();

        RentalRequest req2 = RentalRequest.builder()
                .id("req-2")
                .listing(listing)
                .ownerId("owner-1")
                .renterId("renter-2")
                .renterEmail("renter2@gmail.com")
                .status(RentalRequestStatus.PENDING)
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req1));
        when(listingRepository.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(rentalRequestRepository.findByListingIdAndStatus("listing-1", RentalRequestStatus.PENDING))
                .thenReturn(List.of(req1, req2));

        RentalRequestResponse res = rentalRequestService.acceptRentalRequest("owner-1", "req-1");

        assertNotNull(res);
        assertEquals(RentalRequestStatus.ACCEPTED, res.status());
        assertNotNull(res.acceptedAt());
        assertNotNull(res.holdExpiresAt());

        // Verify listing marked reserved
        verify(listingStatusService, times(1)).markReserved(listing, "owner-1");

        // Verify req2 was cancelled by system
        assertEquals(RentalRequestStatus.CANCELLED_BY_SYSTEM, req2.getStatus());
        verify(rentalRequestRepository, atLeast(2)).save(any(RentalRequest.class));

        // Verify HELD reservation is created on accept
        verify(parkingReservationService, times(1)).createHeldReservations(
                eq(req1), eq(listing), eq(req1.getHoldExpiresAt())
        );
    }

    @Test
    void rejectRentalRequest_success() {
        Listing listing = Listing.builder().id("listing-1").ownerId("owner-1").build();
        RentalRequest req = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .ownerId("owner-1")
                .status(RentalRequestStatus.PENDING)
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RentalRequestResponse res = rentalRequestService.rejectRentalRequest(
                "owner-1", "req-1", new RejectRentalRequest("Khong phu hop"));

        assertEquals(RentalRequestStatus.REJECTED, res.status());
        assertEquals("Khong phu hop", res.rejectReason());
    }

    @Test
    void cancelRentalRequest_byRenter_success() {
        Listing listing = Listing.builder().id("listing-1").ownerId("owner-1").build();
        RentalRequest req = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .ownerId("owner-1")
                .renterId("renter-1")
                .status(RentalRequestStatus.PENDING)
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        RentalRequestResponse res = rentalRequestService.cancelRentalRequest("renter-1", "req-1");
        assertEquals(RentalRequestStatus.CANCELLED_BY_RENTER, res.status());
    }

    @Test
    void expirePendingHoldRequests_restoresListingToPublished() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .status(ListingStatus.RESERVED)
                .build();

        RentalRequest expiredReq = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .status(RentalRequestStatus.ACCEPTED)
                .holdExpiresAt(Instant.now().minusSeconds(3600))
                .build();

        when(rentalRequestRepository.findAllByStatusAndHoldExpiresAtLessThanEqual(eq(RentalRequestStatus.ACCEPTED), any(Instant.class)))
                .thenReturn(List.of(expiredReq));

        int expiredCount = rentalRequestService.expirePendingHoldRequests(Instant.now());

        assertEquals(1, expiredCount);
        assertEquals(RentalRequestStatus.EXPIRED, expiredReq.getStatus());
        verify(listingStatusService, times(1)).releaseReserved(eq(listing), eq("SYSTEM"), anyString());
    }

    @Test
    void expirePendingHoldRequests_keepsRequestWhenContractIsProtected() {
        RentalHoldProtectionChecker checker = mock(RentalHoldProtectionChecker.class);
        rentalRequestService.setHoldProtectionCheckers(List.of(checker));

        Listing listing = Listing.builder()
                .id("listing-1")
                .status(ListingStatus.RESERVED)
                .build();
        RentalRequest request = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .status(RentalRequestStatus.ACCEPTED)
                .holdExpiresAt(Instant.now().minusSeconds(60))
                .build();

        when(checker.isProtected("req-1")).thenReturn(true);
        when(rentalRequestRepository.findAllByStatusAndHoldExpiresAtLessThanEqual(
                eq(RentalRequestStatus.ACCEPTED), any(Instant.class)))
                .thenReturn(List.of(request));

        int expiredCount = rentalRequestService.expirePendingHoldRequests(Instant.now());

        assertEquals(0, expiredCount);
        assertEquals(RentalRequestStatus.ACCEPTED, request.getStatus());
        verify(rentalRequestRepository, never()).save(request);
        verify(listingStatusService, never()).releaseReserved(any(), anyString(), anyString());
    }

    @Test
    void createRentalRequest_exceedsApartmentOccupantLimit_throwsException() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .category(com.hs.listing.model.constant.ListingCategory.APARTMENT)
                .status(ListingStatus.PUBLISHED)
                .apartmentDetail(com.hs.listing.model.ListingApartmentDetail.builder().maxOccupants(2).build())
                .priceAmount(BigDecimal.valueOf(5000000))
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 3, 0, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        AppException ex = assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest("renter-1", "a@gmail.com", req));
        assertEquals(ListingErrorCode.OCCUPANT_LIMIT_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void createRentalRequest_motorbikeNotAllowed_throwsException() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .category(com.hs.listing.model.constant.ListingCategory.APARTMENT)
                .status(ListingStatus.PUBLISHED)
                .priceAmount(BigDecimal.valueOf(5000000))
                .maxMotorbikeCount(0) // capacity = 0
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        when(parkingReservationService.getAvailability(any(), any(), eq(com.hs.listing.model.constant.VehicleType.MOTORBIKE), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(0, 0, 0));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, 1, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        AppException ex = assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest("renter-1", "a@gmail.com", req));
        assertEquals(ListingErrorCode.MOTORBIKE_PARKING_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    void createRentalRequest_carCapacityExceeded_throwsException() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .category(com.hs.listing.model.constant.ListingCategory.APARTMENT)
                .status(ListingStatus.PUBLISHED)
                .priceAmount(BigDecimal.valueOf(5000000))
                .maxCarCount(2)
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        when(parkingReservationService.getAvailability(any(), any(), eq(com.hs.listing.model.constant.VehicleType.CAR), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(2, 1, 1)); // only 1 available

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, 0, 2, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        AppException ex = assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest("renter-1", "a@gmail.com", req));
        assertEquals(ListingErrorCode.CAR_CAPACITY_EXCEEDED.getCode(), ex.getCode());
    }

    @Test
    void rejectAndCancelRentalRequest_releasesParkingReservations() {
        Listing listing = Listing.builder().id("listing-1").ownerId("owner-1").build();
        RentalRequest req = RentalRequest.builder()
                .id("req-1")
                .listing(listing)
                .ownerId("owner-1")
                .renterId("renter-1")
                .status(RentalRequestStatus.PENDING)
                .build();

        when(rentalRequestRepository.findById("req-1")).thenReturn(Optional.of(req));
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        // Reject
        rentalRequestService.rejectRentalRequest("owner-1", "req-1", new RejectRentalRequest("Reject"));
        verify(parkingReservationService, times(1)).releaseReservationsForRequest("req-1");

        // Cancel
        req.setStatus(RentalRequestStatus.PENDING);
        rentalRequestService.cancelRentalRequest("renter-1", "req-1");
        verify(parkingReservationService, times(2)).releaseReservationsForRequest("req-1");
    }

    @Test
    void snapshotRemainsImmutableWhenListingChargesChangeAfterwards() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .title("Can ho 2PN")
                .status(ListingStatus.PUBLISHED)
                .depositType(DepositType.FIXED_AMOUNT)
                .priceAmount(BigDecimal.valueOf(5000000))
                .depositAmount(BigDecimal.valueOf(5000000))
                .maxMotorbikeCount(2)
                .maxCarCount(1)
                .charges(new java.util.ArrayList<>())
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        when(rentalRequestRepository.findFirstByListingIdAndRenterIdAndStatusIn(anyString(), anyString(), anyCollection()))
                .thenReturn(Optional.empty());
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(parkingReservationService.getAvailability(any(), any(), any(), any(), any()))
                .thenReturn(new VehicleAvailabilityInfo(2, 0, 2));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, 0, 0, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        RentalRequestResponse res = rentalRequestService.createRentalRequest("renter-1", "a@gmail.com", req);

        // Snapshot is saved with 5000000
        assertEquals(new BigDecimal("5000000"), res.effectiveMonthlyRent());
        assertEquals(new BigDecimal("5000000"), res.estimatedMonthlyTotal());

        // Now mutate listing price
        listing.setPriceAmount(BigDecimal.valueOf(99000000));

        // Request entity still preserves original snapshot values
        assertEquals(new BigDecimal("5000000"), res.effectiveMonthlyRent());
        assertEquals(new BigDecimal("5000000"), res.estimatedMonthlyTotal());
    }
}
