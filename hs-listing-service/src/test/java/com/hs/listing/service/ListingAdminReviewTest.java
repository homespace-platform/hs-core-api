package com.hs.listing.service;

import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.dto.response.ListingDetailResponse;
import com.hs.listing.dto.response.ListingAddressResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingAddress;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.*;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingAdminReviewTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ListingStatusHistoryRepository history = mock(ListingStatusHistoryRepository.class);
    private final ListingQueryService query = mock(ListingQueryService.class);
    private final ListingStatusService statuses = mock(ListingStatusService.class);
    private final ListingService content = mock(ListingService.class);
    private final ListingAdminService admin = new ListingAdminService(listings, history, query, statuses, content,
            mock(ListingAddressRepository.class), mock(UserRepository.class), mock(StorageProperties.class));

    @Test void statusCountsDoNotDoubleCountPendingEdits() {
        when(listings.countAllGroupedByStatus()).thenReturn(java.util.Arrays.asList(
                new Object[]{ListingStatus.PENDING_REVIEW,1L},new Object[]{ListingStatus.PUBLISHED,2L}));
        var counts=admin.getStatusCounts();
        assertEquals(1L,counts.get("PENDING_REVIEW")); assertEquals(3L,counts.get("ALL"));
        verify(listings,never()).countByActiveTrueAndPendingEditIdIsNotNull();
    }

    @ParameterizedTest @EnumSource(value=ListingStatus.class, names={"RESERVED","RENTED","PUBLISHED","HIDDEN","EXPIRED"})
    void adminReadsTheSubmittedContentAsANormalListingWithoutChangingItsLiveState(ListingStatus status) {
        var live = Listing.builder().id("listing").status(ListingStatus.PENDING_REVIEW).reviewOperationalStatus(status)
                .reviewOriginStatus(status).title("Đang áp dụng").pendingEditId("reviewed-edit").build();
        var edit = mock(CreateListingRequest.class);
        var proposed = Listing.builder().id("listing").status(status).title("Nội dung đã gửi").build();
        var address = new ListingAddress(); address.setDisplay("Địa chỉ đã gửi");
        when(listings.findByIdAndActiveTrue("listing")).thenReturn(Optional.of(live));
        when(content.pendingEdit(live)).thenReturn(edit);
        when(content.reviewContent(live,edit)).thenReturn(new ListingService.ReviewContent(proposed,address));
        var detail = mock(ListingDetailResponse.class);
        when(query.toDetail(proposed, ListingAddressResponse.from(address))).thenReturn(detail);

        var response = admin.getById("listing");

        assertSame(detail,response.listing()); assertEquals("reviewed-edit",response.pendingEditId());
        assertEquals("Địa chỉ đã gửi",response.pendingAddress());
        assertEquals(ListingStatus.PENDING_REVIEW,live.getStatus()); assertEquals(status,live.getOperationalStatus());
        assertEquals("Đang áp dụng",live.getTitle());
        verify(query,never()).toDetail(live); verify(listings,never()).save(any()); verify(listings,never()).flush();
        verify(statuses).hasProtectedRental(live);
    }

    @Test void normalListingsContinueUsingTheirApprovedContent() {
        var live = Listing.builder().id("listing").status(ListingStatus.PENDING_REVIEW).build();
        when(listings.findByIdAndActiveTrue("listing")).thenReturn(Optional.of(live));
        var detail = mock(ListingDetailResponse.class); when(query.toDetail(live)).thenReturn(detail);
        var response = admin.getById("listing");
        assertSame(detail,response.listing()); assertNull(response.pendingEditId()); assertNull(response.pendingAddress());
        verify(query,never()).toDetail(any(),any(ListingAddressResponse.class));
    }

    @Test void aRemovedBranchOrCatalogDoesNotPreventTheAdminFromReadingAndRejectingTheSubmission() {
        var live = Listing.builder().id("listing").status(ListingStatus.RESERVED).pendingEditId("reviewed-edit").build();
        var edit = mock(CreateListingRequest.class);
        when(listings.findByIdAndActiveTrue("listing")).thenReturn(Optional.of(live));
        when(content.pendingEdit(live)).thenReturn(edit);
        when(content.reviewContent(live,edit)).thenThrow(new com.hs.common.advice.entity.AppException(
                409,"Chi nhánh đã ngừng hoạt động",org.springframework.http.HttpStatus.CONFLICT));
        var detail = mock(ListingDetailResponse.class); when(query.toDetail(live)).thenReturn(detail);
        var response = admin.getById("listing");
        assertSame(detail,response.listing()); assertEquals("reviewed-edit",response.pendingEditId());
        assertEquals("Chi nhánh đã ngừng hoạt động",response.reviewError());
        assertEquals(ListingStatus.RESERVED,live.getStatus()); verify(listings,never()).save(any());
    }
}
