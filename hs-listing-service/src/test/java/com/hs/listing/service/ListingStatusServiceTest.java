package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingStatusServiceTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ListingStatusHistoryRepository history = mock(ListingStatusHistoryRepository.class);
    private final ListingStatusService service = new ListingStatusService(
            listings, history, 30);

    @Test
    void submittingDraftMovesItToPendingReviewAndWritesHistory() {
        Listing listing = listing(ListingStatus.DRAFT);

        service.applySubmission(
                listing, ListingSubmissionAction.SUBMIT_FOR_REVIEW, "owner-1", ListingStatusActorType.USER);

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
        assertNotNull(listing.getSubmittedAt());
        ArgumentCaptor<ListingStatusHistory> event = ArgumentCaptor.forClass(ListingStatusHistory.class);
        verify(history).save(event.capture());
        assertEquals(ListingStatus.DRAFT, event.getValue().getFromStatus());
        assertEquals(ListingStatus.PENDING_REVIEW, event.getValue().getToStatus());
    }

    @Test
    void approvingPendingListingSetsPublicationWindow() {
        Listing listing = listing(ListingStatus.PENDING_REVIEW);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.PUBLISHED, null);

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertNotNull(listing.getPublishedAt());
        assertEquals(30, ChronoUnit.DAYS.between(listing.getPublishedAt(), listing.getExpiresAt()));
    }

    @Test
    void rejectingPublishedListingIsNotAllowed() {
        Listing listing = listing(ListingStatus.PUBLISHED);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByAdmin("admin-1", "listing-1", ListingStatus.REJECTED, "reason"));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCannotEditViolationStatus() {
        Listing listing = listing(ListingStatus.VIOLATION);

        assertThrows(AppException.class, () -> service.applySubmission(
                listing, ListingSubmissionAction.SAVE_DRAFT, "owner-1", ListingStatusActorType.USER));
    }

    private Listing listing(ListingStatus status) {
        Listing listing = Listing.builder()
                .id("listing-1")
                .ownerId("owner-1")
                .status(status)
                .build();
        listing.setActive(true);
        return listing;
    }
}
