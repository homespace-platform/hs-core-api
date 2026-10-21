package com.hs.listing.service;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingQueryServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ListingStatus.class)
    void onlyPublicStatusesAreReadableByGuestsAndUnrelatedViewers(ListingStatus status) {
        var repository=mock(ListingRepository.class);
        var rentals=mock(com.hs.listing.repository.RentalRequestRepository.class);
        var listing=listing();listing.setStatus(status);
        when(repository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        var service=accessService(repository,rentals);
        for(String viewer:java.util.Arrays.asList(null,"unrelated")) {
            if(status==ListingStatus.PUBLISHED || status==ListingStatus.RESERVED)
                assertEquals("listing-1",service.getById(viewer,"listing-1").id());
            else assertEquals(404,assertThrows(com.hs.common.advice.entity.AppException.class,
                ()->service.getById(viewer,"listing-1")).getStatusCode().value());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ListingStatus.class)
    void ownerCanReadOwnActiveListingInEveryStatus(ListingStatus status) {
        var repository=mock(ListingRepository.class);
        var listing=listing();listing.setStatus(status);
        when(repository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        var service=accessService(repository,mock(com.hs.listing.repository.RentalRequestRepository.class));
        assertEquals("owner-1",service.getById("owner-1","listing-1").ownerId());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ListingStatus.class)
    void relatedRenterCanReadButDoesNotBecomeTheOwner(ListingStatus status) {
        var repository=mock(ListingRepository.class);
        var rentals=mock(com.hs.listing.repository.RentalRequestRepository.class);
        var listing=listing();listing.setStatus(status);
        when(repository.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));
        when(rentals.existsByListingIdAndRenterId("listing-1","tenant")).thenReturn(true);
        var service=accessService(repository,rentals);
        assertEquals("owner-1",service.getById("tenant","listing-1").ownerId());
        if(status!=ListingStatus.PUBLISHED && status!=ListingStatus.RESERVED)
            assertThrows(com.hs.common.advice.entity.AppException.class,()->service.getById("other-tenant","listing-1"));
    }
    @Test void deletedOrMissingListingCannotBeReadEvenByOwnerOrRelatedRenter() {
        var service=accessService(mock(ListingRepository.class),mock(com.hs.listing.repository.RentalRequestRepository.class));
        for(String viewer:List.of("owner-1","tenant"))
            assertEquals(404,assertThrows(com.hs.common.advice.entity.AppException.class,
                ()->service.getById(viewer,"missing")).getStatusCode().value());
    }
    private ListingQueryService accessService(ListingRepository listings,com.hs.listing.repository.RentalRequestRepository rentals) {
        return new ListingQueryService(listings,mock(AddressRepository.class),mock(UserRepository.class),properties(),rentals,
            mock(com.hs.listing.repository.PropertyBranchRepository.class));
    }
    @Test
    void returnsTenOwnedListingsPerPage() {
        ListingRepository listings = mock(ListingRepository.class);
        AddressRepository addresses = mock(AddressRepository.class);
        Listing listing = listing();
        when(listings.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(addresses.findByListingIdAndActiveTrue("listing-1")).thenReturn(Optional.empty());
        ListingQueryService service = new ListingQueryService(
                listings, addresses, mock(UserRepository.class), properties(), mock(com.hs.listing.repository.RentalRequestRepository.class), mock(com.hs.listing.repository.PropertyBranchRepository.class));

        var response = service.getMyListings("owner-1", 2, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listings).findAll(any(Specification.class), pageable.capture());
        assertEquals(10, pageable.getValue().getPageSize());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals("listing-1", response.getResult().getFirst().id());
    }

    private Listing listing() {
        Listing listing = Listing.builder()
                .id("listing-1").ownerId("owner-1").title("Listing")
                .description("Description").category(ListingCategory.ROOM)
                .status(ListingStatus.PUBLISHED).availableFrom(LocalDate.now())
                .areaM2(BigDecimal.TEN).priceAmount(BigDecimal.TEN).currency("VND")
                .priceUnit(PriceUnit.ROOM_MONTH).depositType(DepositType.NONE)
                .paymentCycle(PaymentCycle.MONTHLY).minimumLeaseMonths(1)
                .publishedAt(Instant.now()).build();
        listing.setActive(true);
        return listing;
    }

    private StorageProperties properties() {
        return new StorageProperties("bucket", "ap-southeast-1", Duration.ofMinutes(10), Duration.ofMinutes(5), null, null);
    }
}
