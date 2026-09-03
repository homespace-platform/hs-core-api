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
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.AddressRepository;
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
    private RentalRequestService rentalRequestService;

    @BeforeEach
    void setUp() {
        rentalRequestRepository = mock(RentalRequestRepository.class);
        listingRepository = mock(ListingRepository.class);
        listingStatusService = mock(ListingStatusService.class);
        addressRepository = mock(AddressRepository.class);
        storageProperties = mock(StorageProperties.class);

        rentalRequestService = new RentalRequestService(
                rentalRequestRepository,
                listingRepository,
                listingStatusService,
                addressRepository,
                storageProperties,
                24
        );
    }

    @Test
    void createRentalRequest_rejectsUnauthenticated() {
        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 1, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
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
                "listing-1", LocalDate.now().plusDays(5), 12, 1, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
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
                "listing-1", LocalDate.now().plusDays(5), 12, 1, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        AppException ex = assertThrows(AppException.class, () -> rentalRequestService.createRentalRequest("renter-1", "r@gmail.com", req));
        assertEquals(ListingErrorCode.LISTING_ALREADY_RESERVED.getCode(), ex.getCode());
    }

    @Test
    void createRentalRequest_success() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .title("Can ho 2PN")
                .status(ListingStatus.PUBLISHED)
                .depositType(DepositType.FIXED_AMOUNT)
                .priceAmount(BigDecimal.valueOf(5000000))
                .depositAmount(BigDecimal.valueOf(5000000))
                .build();
        when(listingRepository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        when(rentalRequestRepository.findFirstByListingIdAndRenterIdAndStatusIn(anyString(), anyString(), anyCollection()))
                .thenReturn(Optional.empty());
        when(rentalRequestRepository.save(any(RentalRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateRentalRequest req = new CreateRentalRequest(
                "listing-1", LocalDate.now().plusDays(5), 12, 2, "Nguyen Van A", "0901234567", "a@gmail.com", null, "Note"
        );

        RentalRequestResponse res = rentalRequestService.createRentalRequest("renter-1", "a@gmail.com", req);

        assertNotNull(res);
        assertEquals("listing-1", res.listingId());
        assertEquals("renter-1", res.renterId());
        assertEquals(RentalRequestStatus.PENDING, res.status());
        assertEquals(2, res.occupantCount());
        verify(rentalRequestRepository, times(1)).save(any(RentalRequest.class));
    }

    @Test
    void acceptRentalRequest_marksReservedAndCancelsOtherPending() {
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
}
