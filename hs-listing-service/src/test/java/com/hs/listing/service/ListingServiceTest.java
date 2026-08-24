package com.hs.listing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    @Mock
    ListingRepository listingRepository;

    @InjectMocks
    ListingService listingService;

    @Test
    void createsDraftOwnedByCurrentUser() {
        CreateListingRequest request = new CreateListingRequest(
                "Phòng trọ gần trường",
                "Phòng sạch, có ban công",
                ListingCategory.ROOM,
                new BigDecimal("3500000"),
                new BigDecimal("3500000"),
                new BigDecimal("25"),
                1,
                1,
                "79",
                "760",
                "26734",
                "12 Nguyễn Huệ",
                null);
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ListingResponse response = listingService.createDraft("user-1", request);

        ArgumentCaptor<Listing> captor = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(captor.capture());
        Listing saved = captor.getValue();
        assertEquals("user-1", saved.getOwnerId());
        assertEquals(ListingStatus.DRAFT, saved.getStatus());
        assertEquals(ListingCategory.ROOM, saved.getCategory());
        assertEquals(saved.getId(), response.id());
        assertEquals("user-1", response.ownerId());
    }
}
