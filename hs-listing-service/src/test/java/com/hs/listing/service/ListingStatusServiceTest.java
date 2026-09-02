package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingStatusServiceTest {
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ListingStatusHistoryRepository history = mock(ListingStatusHistoryRepository.class);
    private final ViewingAppointmentService appointments = mock(ViewingAppointmentService.class);
    private final ListingStatusService service = new ListingStatusService(
            listings, history, appointments, 30);

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

    @Test
    void ownerCanMarkPublishedListingAsRentedOutsideThePlatform() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.RENTED_EXTERNALLY, "Khách tự liên hệ");

        assertEquals(ListingStatus.RENTED_EXTERNALLY, listing.getStatus());
        assertEquals("Khách tự liên hệ", listing.getStatusReason());
        ArgumentCaptor<ListingStatusHistory> event = ArgumentCaptor.forClass(ListingStatusHistory.class);
        verify(history).save(event.capture());
        assertEquals(ListingStatus.PUBLISHED, event.getValue().getFromStatus());
        assertEquals(ListingStatus.RENTED_EXTERNALLY, event.getValue().getToStatus());
    }

    @Test
    void restoringListingKeepsRemainingPublicationWindow() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        Instant publishedAt = listing.getPublishedAt();
        Instant expiresAt = listing.getExpiresAt();
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null);

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertEquals(publishedAt, listing.getPublishedAt());
        assertEquals(expiresAt, listing.getExpiresAt());
    }

    @Test
    void restoringListingAfterPublicationWindowEndedIsRejected() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(-1));
        listing.setStatus(ListingStatus.HIDDEN);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCannotMarkDraftListingAsRentedExternally() {
        Listing listing = listing(ListingStatus.DRAFT);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.RENTED_EXTERNALLY, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCanResubmitExpiredListingForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(-1));
        listing.setStatus(ListingStatus.EXPIRED);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null);

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
        assertNotNull(listing.getSubmittedAt());
    }

    @Test
    void ownerCannotManuallyMarkListingAsRented() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.RENTED, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCannotChangeListingWithActiveContract() {
        Listing listing = listing(ListingStatus.RENTED);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.HIDDEN, null));
        verify(history, never()).save(any());
    }

    @Test
    void hiddenListingWithOpenWindowCannotBeResubmittedForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.HIDDEN);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null));
        verify(history, never()).save(any());
    }

    @Test
    void hiddenListingWithExpiredWindowCanBeResubmittedForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(-1));
        listing.setStatus(ListingStatus.HIDDEN);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null);

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
    }

    @Test
    void ownerCannotResubmitExternallyRentedListingForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCanRepublishExternallyRentedListingWhenWindowRemains() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null);

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
    }

    @Test
    void adminCanMoveListingBetweenRentedStates() {
        Listing listing = listing(ListingStatus.RENTED);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.RENTED_EXTERNALLY, null);

        assertEquals(ListingStatus.RENTED_EXTERNALLY, listing.getStatus());
    }

    @Test
    void adminCanUnlockViolationListingBySendingToPendingReview() {
        Listing listing = listing(ListingStatus.VIOLATION);
        listing.setStatusReason("Spam content");
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.PENDING_REVIEW, "Đã xử lý khiếu nại");

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
        assertEquals("Đã xử lý khiếu nại", listing.getStatusReason());
        assertNotNull(listing.getSubmittedAt());
    }

    @Test
    void adminCanUnlockViolationListingByPublishingAgain() {
        Listing listing = listing(ListingStatus.VIOLATION);
        when(listings.findByIdAndActiveTrue("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.PUBLISHED, "Khóa nhầm, mở lại");

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertNotNull(listing.getPublishedAt());
        assertNotNull(listing.getExpiresAt());
        assertEquals(30, ChronoUnit.DAYS.between(listing.getPublishedAt(), listing.getExpiresAt()));
    }

    private Listing publishedListingWithWindow(Duration remaining) {
        Listing listing = listing(ListingStatus.PUBLISHED);
        Instant now = Instant.now();
        listing.setPublishedAt(now.minus(Duration.ofDays(5)));
        listing.setExpiresAt(now.plus(remaining));
        return listing;
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
