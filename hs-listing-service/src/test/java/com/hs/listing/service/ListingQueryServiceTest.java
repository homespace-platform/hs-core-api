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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingQueryServiceTest {
    @Test
    void returnsTenOwnedListingsPerPage() {
        ListingRepository listings = mock(ListingRepository.class);
        AddressRepository addresses = mock(AddressRepository.class);
        Listing listing = listing();
        when(listings.findAllByOwnerIdAndActiveTrue(eq("owner-1"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(listing)));
        when(addresses.findByListingIdAndActiveTrue("listing-1")).thenReturn(Optional.empty());
        ListingQueryService service = new ListingQueryService(
                listings, addresses, mock(UserRepository.class), properties());

        var response = service.getMyListings("owner-1", 2);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(listings).findAllByOwnerIdAndActiveTrue(eq("owner-1"), pageable.capture());
        assertEquals(10, pageable.getValue().getPageSize());
        assertEquals(1, pageable.getValue().getPageNumber());
        assertEquals("listing-1", response.getResult().getFirst().id());
    }

    private Listing listing() {
        Listing listing = Listing.builder()
                .id("listing-1").ownerId("owner-1").title("Listing")
                .description("Description").category(ListingCategory.ROOM)
                .subtype(ListingSubtype.ROOM_BOARDING).rentalMode(RentalMode.WHOLE_UNIT)
                .status(ListingStatus.PUBLISHED).availableFrom(LocalDate.now())
                .areaM2(BigDecimal.TEN).priceAmount(BigDecimal.TEN).currency("VND")
                .priceUnit(PriceUnit.ROOM_MONTH).depositType(DepositType.NONE)
                .paymentCycle(PaymentCycle.MONTHLY).minimumLeaseMonths(1)
                .publishedAt(Instant.now()).build();
        listing.setActive(true);
        return listing;
    }

    private StorageProperties properties() {
        return new StorageProperties("bucket", "ap-southeast-1", Duration.ofMinutes(10), Duration.ofMinutes(5));
    }
}
