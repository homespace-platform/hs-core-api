package com.hs.listing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.request.AddListingImageRequest;
import com.hs.listing.dto.request.UpdateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.common.advice.entity.AppException;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingImage;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingImageRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.service.StorageService;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    @Mock
    ListingRepository listingRepository;

    @Mock
    ListingImageRepository listingImageRepository;

    @Mock
    StorageService storageService;

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

    @Test
    void updatesOnlyProvidedFieldsOnOwnedDraft() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Tiêu đề cũ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.DRAFT)
                .build();
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(listing));
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ListingResponse response = listingService.updateDraft(
                "user-1", "listing-1",
                new UpdateListingRequest("Tiêu đề mới", null, new BigDecimal("4000000"), null, null,
                        null, null, null, null, null, null, null));

        assertEquals("Tiêu đề mới", listing.getTitle());
        assertEquals(new BigDecimal("4000000"), listing.getPriceMonthly());
        assertEquals(ListingCategory.ROOM, listing.getCategory());
        assertEquals("listing-1", response.id());
        verify(listingRepository).save(listing);
    }

    @Test
    void attachesReadyPropertyImageOwnedByUser() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Phòng trọ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.DRAFT)
                .build();
        StorageObjectResponse storage = new StorageObjectResponse(
                "storage-1", "room.png", "image/png", 100L, null, "png", "user-1",
                "PROPERTY", "listing-1", StoragePurpose.PROPERTY_IMAGE, StorageVisibility.PUBLIC,
                StorageStatus.READY, Instant.now(), Instant.now());
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(listing));
        when(storageService.getById("storage-1")).thenReturn(storage);
        when(listingImageRepository.existsByListingIdAndStorageId("listing-1", "storage-1"))
                .thenReturn(false);
        when(listingImageRepository.save(any(ListingImage.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ListingResponse response = listingService.addImage(
                "user-1", "listing-1", new AddListingImageRequest("storage-1", 0, true));

        verify(listingImageRepository).save(any(ListingImage.class));
        assertEquals("listing-1", response.id());
        verify(storageService).getById("storage-1");
        verify(listingRepository, never()).save(any(Listing.class));
    }

    @Test
    void publishesOwnedDraft() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Phòng trọ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.DRAFT)
                .build();
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(listing));
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ListingResponse response = listingService.publish("user-1", "listing-1");

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertEquals(ListingStatus.PUBLISHED, response.status());
        verify(listingRepository).save(listing);
    }

    @Test
    void getsPublishedListingForAnonymousViewer() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Phòng trọ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.PUBLISHED)
                .build();
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(listing));

        ListingResponse response = listingService.getById(null, "listing-1");

        assertEquals("listing-1", response.id());
        assertEquals(ListingStatus.PUBLISHED, response.status());
    }

    @Test
    void hidesDraftFromOtherViewer() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Phòng trọ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.DRAFT)
                .build();
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class, () -> listingService.getById("user-2", "listing-1"));
    }

    @Test
    void getsOnlyPublishedAndRentedListings() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Phòng trọ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.PUBLISHED)
                .build();
        var pageable = PageRequest.of(0, 20);
        var statuses = List.of(ListingStatus.PUBLISHED, ListingStatus.RENTED);
        when(listingRepository.findAllByStatusIn(statuses, pageable))
                .thenReturn(new PageImpl<>(List.of(listing), pageable, 1));

        PageResponse<ListingResponse> response = listingService.getAll(pageable);

        assertEquals(1, response.getTotalElements());
        assertEquals("listing-1", response.getResult().get(0).id());
        verify(listingRepository).findAllByStatusIn(statuses, pageable);
    }
}
