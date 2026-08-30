package com.hs.listing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.request.ListingAddressRequest;
import com.hs.listing.dto.request.UpdateListingRequest;
import com.hs.listing.dto.response.ListingResponse;
import com.hs.common.advice.entity.AppException;
import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingMediaType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.user.model.Address;
import com.hs.user.model.User;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ListingServiceTest {

    private static final String IMAGE_URL =
            "https://homespace-dev-files.s3.ap-southeast-1.amazonaws.com/listing_image/user-1/img-1.png";
    private static final String VIDEO_URL =
            "https://homespace-dev-files.s3.ap-southeast-1.amazonaws.com/listing_video/user-1/vid-1.mp4";
    private static final ListingAddressRequest ADDRESS_REQUEST = new ListingAddressRequest(
            "79",
            "Hồ Chí Minh",
            "26734",
            "An Nhơn",
            "12 Nguyễn Huệ");

    @Mock
    ListingRepository listingRepository;

    @Mock
    AddressRepository addressRepository;

    @Mock
    UserRepository userRepository;

    @Mock
    ListingMediaService listingMediaService;

    private ListingService listingService;

    @BeforeEach
    void setUp() {
        listingService = new ListingService(
                listingRepository, addressRepository, userRepository, listingMediaService, new ObjectMapper());
    }

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
                ADDRESS_REQUEST,
                null,
                List.of(IMAGE_URL),
                List.of(VIDEO_URL));
        when(listingMediaService.isAllowedMediaUrl("user-1", IMAGE_URL, ListingMediaType.IMAGE)).thenReturn(true);
        when(listingMediaService.isAllowedMediaUrl("user-1", VIDEO_URL, ListingMediaType.VIDEO)).thenReturn(true);
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> {
            Listing listing = invocation.getArgument(0);
            listing.setId("listing-1");
            return listing;
        });
        when(addressRepository.save(any(Address.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("user-1")).thenReturn(Optional.of(ownerUser()));

        ListingResponse response = listingService.createDraft("user-1", request);

        ArgumentCaptor<Listing> listingCaptor = ArgumentCaptor.forClass(Listing.class);
        verify(listingRepository).save(listingCaptor.capture());
        Listing saved = listingCaptor.getValue();
        assertEquals("user-1", saved.getOwnerId());
        assertEquals(ListingStatus.DRAFT, saved.getStatus());
        assertEquals("[\"" + IMAGE_URL + "\"]", saved.getImageUrlsJson());
        assertEquals("[\"" + VIDEO_URL + "\"]", saved.getVideoUrlsJson());

        ArgumentCaptor<Address> addressCaptor = ArgumentCaptor.forClass(Address.class);
        verify(addressRepository).save(addressCaptor.capture());
        Address savedAddress = addressCaptor.getValue();
        assertEquals("listing-1", savedAddress.getListingId());
        assertEquals("12 Nguyễn Huệ, An Nhơn, Hồ Chí Minh", savedAddress.getFullAddress());

        assertEquals(List.of(IMAGE_URL), response.imageUrls());
        assertEquals(List.of(VIDEO_URL), response.videoUrls());
        assertEquals("12 Nguyễn Huệ", response.address().streetLine());
    }

    @Test
    void updatesImageAndVideoUrlsOnOwnedDraft() {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("user-1")
                .title("Phòng trọ")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.DRAFT)
                .build();
        when(listingRepository.findById("listing-1")).thenReturn(Optional.of(listing));
        when(listingMediaService.isAllowedMediaUrl("user-1", IMAGE_URL, ListingMediaType.IMAGE)).thenReturn(true);
        when(listingMediaService.isAllowedMediaUrl("user-1", VIDEO_URL, ListingMediaType.VIDEO)).thenReturn(true);
        when(listingRepository.save(any(Listing.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(addressRepository.findByListingIdAndActiveTrue("listing-1")).thenReturn(Optional.empty());
        when(userRepository.findById("user-1")).thenReturn(Optional.of(ownerUser()));

        ListingResponse response = listingService.updateDraft(
                "user-1",
                "listing-1",
                new UpdateListingRequest(null, null, null, null, null, null, null, null, null,
                        List.of(IMAGE_URL), List.of(VIDEO_URL)));

        assertEquals("[\"" + IMAGE_URL + "\"]", listing.getImageUrlsJson());
        assertEquals("[\"" + VIDEO_URL + "\"]", listing.getVideoUrlsJson());
        assertEquals(List.of(IMAGE_URL), response.imageUrls());
        assertEquals(List.of(VIDEO_URL), response.videoUrls());
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
        when(addressRepository.findByListingIdAndActiveTrue("listing-1")).thenReturn(Optional.empty());
        when(userRepository.findById("user-1")).thenReturn(Optional.of(ownerUser()));

        ListingResponse response = listingService.publish("user-1", "listing-1");

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertEquals(ListingStatus.PUBLISHED, response.status());
        verify(listingRepository).save(listing);
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
        when(addressRepository.findByListingIdAndActiveTrue("listing-1")).thenReturn(Optional.empty());
        when(userRepository.findById("user-1")).thenReturn(Optional.of(ownerUser()));

        PageResponse<ListingResponse> response = listingService.getAll(pageable);

        assertEquals(1, response.getTotalElements());
        assertEquals("listing-1", response.getResult().get(0).id());
        verify(listingRepository).findAllByStatusIn(statuses, pageable);
    }

    private static User ownerUser() {
        User user = new User();
        user.setId("user-1");
        user.setUsername("owner");
        user.setFirstName("System");
        user.setLastName("Administrator");
        user.setPhone("0353999798");
        return user;
    }
}
