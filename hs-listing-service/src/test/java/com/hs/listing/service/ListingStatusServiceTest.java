package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        var nearbyEvents = mock(org.springframework.context.ApplicationEventPublisher.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "nearbyEvents", nearbyEvents);

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.PUBLISHED, null);

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertNotNull(listing.getPublishedAt());
        assertEquals(30, ChronoUnit.DAYS.between(listing.getPublishedAt(), listing.getExpiresAt()));
        verify(nearbyEvents).publishEvent(new com.hs.listing.nearby.NearbyTypes.ScanChanged("listing-1"));
    }

    @Test
    void rejectingPublishedListingIsNotAllowed() {
        Listing listing = listing(ListingStatus.PUBLISHED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

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
    void verifiedTerminationRestoresRentedListingWithFreshPublicationWindow() {
        Listing listing = listing(ListingStatus.RENTED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.releaseRentedAfterTermination("listing-1", "owner-1");

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertEquals(LocalDate.now(), listing.getAvailableFrom());
        assertEquals(30, ChronoUnit.DAYS.between(listing.getPublishedAt(), listing.getExpiresAt()));
        verify(history).save(any(ListingStatusHistory.class));
    }

    @Test
    void applySubmissionRejectsRentedStatus() {
        Listing listing = listing(ListingStatus.RENTED);

        AppException ex = assertThrows(AppException.class, () -> service.applySubmission(
                listing, ListingSubmissionAction.SAVE_DRAFT, "owner-1", ListingStatusActorType.USER));
        assertEquals(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT.getCode(), ex.getCode());

        AppException exReview = assertThrows(AppException.class, () -> service.applySubmission(
                listing, ListingSubmissionAction.SUBMIT_FOR_REVIEW, "owner-1", ListingStatusActorType.USER));
        assertEquals(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT.getCode(), exReview.getCode());

        verify(history, never()).save(any());
    }

    @Test
    void ownerCanMarkPublishedListingAsRentedOutsideThePlatform() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

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
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null);

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertEquals(publishedAt, listing.getPublishedAt());
        assertEquals(expiresAt, listing.getExpiresAt());
    }

    @Test
    void restoringListingAfterPublicationWindowEndedIsRejected() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(-1));
        listing.setStatus(ListingStatus.HIDDEN);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCannotMarkDraftListingAsRentedExternally() {
        Listing listing = listing(ListingStatus.DRAFT);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.RENTED_EXTERNALLY, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCanResubmitExpiredListingForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(-1));
        listing.setStatus(ListingStatus.EXPIRED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null);

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
        assertNotNull(listing.getSubmittedAt());
    }

    @Test
    void ownerCannotManuallyMarkListingAsRented() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.RENTED, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCannotChangeListingWithActiveContract() {
        Listing listing = listing(ListingStatus.RENTED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.HIDDEN, null));
        verify(history, never()).save(any());
    }

    @Test
    void contractFlowCanMarkReservedListingAsRented() {
        Listing listing = listing(ListingStatus.RESERVED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.markRentedByContract("listing-1", "tenant-1");

        assertEquals(ListingStatus.RENTED, listing.getStatus());
        ArgumentCaptor<ListingStatusHistory> event = ArgumentCaptor.forClass(ListingStatusHistory.class);
        verify(history).save(event.capture());
        assertEquals(ListingStatus.RESERVED, event.getValue().getFromStatus());
        assertEquals(ListingStatus.RENTED, event.getValue().getToStatus());
        assertEquals("tenant-1", event.getValue().getChangedBy());
    }

    @Test
    void hiddenListingWithOpenWindowCannotBeResubmittedForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.HIDDEN);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null));
        verify(history, never()).save(any());
    }

    @Test
    void hiddenListingWithExpiredWindowCanBeResubmittedForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(-1));
        listing.setStatus(ListingStatus.HIDDEN);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null);

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
    }

    @Test
    void ownerCannotResubmitExternallyRentedListingForReview() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        assertThrows(AppException.class,
                () -> service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null));
        verify(history, never()).save(any());
    }

    @Test
    void ownerCanRepublishExternallyRentedListingWhenWindowRemains() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(10));
        listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null);

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
    }

    @Test
    void adminCannotMarkHomeSpaceRentedListingAsRentedExternally() {
        Listing listing = listing(ListingStatus.RENTED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        AppException ex = assertThrows(AppException.class,
                () -> service.changeByAdmin("admin-1", "listing-1", ListingStatus.RENTED_EXTERNALLY, null));

        assertEquals(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT.getCode(), ex.getCode());
        assertEquals(ListingStatus.RENTED, listing.getStatus());
        verify(listings, never()).save(any());
        verify(history, never()).save(any());
    }

    @Test
    void adminCanMarkPublishedListingAsRentedExternally() {
        Listing listing = listing(ListingStatus.PUBLISHED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.RENTED_EXTERNALLY, null);

        assertEquals(ListingStatus.RENTED_EXTERNALLY, listing.getStatus());
        verify(history).save(any());
    }

    @Test
    void adminCanUnlockViolationListingBySendingToPendingReview() {
        Listing listing = listing(ListingStatus.VIOLATION);
        listing.setStatusReason("Spam content");
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.PENDING_REVIEW, "Đã xử lý khiếu nại");

        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
        assertEquals("Đã xử lý khiếu nại", listing.getStatusReason());
        assertNotNull(listing.getSubmittedAt());
    }

    @Test
    void adminCanUnlockViolationListingByPublishingAgain() {
        Listing listing = listing(ListingStatus.VIOLATION);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));

        service.changeByAdmin("admin-1", "listing-1", ListingStatus.PUBLISHED, "Khóa nhầm, mở lại");

        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertNotNull(listing.getPublishedAt());
        assertNotNull(listing.getExpiresAt());
        assertEquals(30, ChronoUnit.DAYS.between(listing.getPublishedAt(), listing.getExpiresAt()));
    }

    @Test
    void expiredExternalRentalCanResubmitAndApprovalStartsANewPublication() {
        Listing listing = publishedListingWithWindow(Duration.ofSeconds(-1));
        listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.changeByOwner("owner-1", "listing-1", ListingStatus.PENDING_REVIEW, null);
        assertEquals(ListingStatus.PENDING_REVIEW, listing.getStatus());
        service.changeByAdmin("admin", "listing-1", ListingStatus.PUBLISHED, null);
        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        assertTrue(listing.getPublishedAt().isAfter(Instant.now().minusSeconds(5)));
        assertEquals(30, ChronoUnit.DAYS.between(listing.getPublishedAt(), listing.getExpiresAt()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ListingStatus.class, names={"DRAFT","REJECTED","EXPIRED"})
    void ordinarySubmissionIsPublishedRatherThanRestoredToTheOldState(ListingStatus initial) {
        Listing listing=listing(initial);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.applySubmission(listing,ListingSubmissionAction.SUBMIT_FOR_REVIEW,"owner-1",ListingStatusActorType.USER);
        service.changeByAdmin("admin","listing-1",ListingStatus.PUBLISHED,null);
        assertEquals(ListingStatus.PUBLISHED,listing.getStatus());
        assertNotNull(listing.getExpiresAt()); assertNull(listing.getReviewOperationalStatus());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ListingStatus.class, names={"PUBLISHED","RESERVED","RENTED","HIDDEN","EXPIRED"})
    void editRequiresReviewAndApprovalRestoresTheCorrectState(ListingStatus initial) {
        Listing listing=publishedListingWithWindow(Duration.ofDays(10)); listing.setStatus(initial);
        Instant originalExpiry=listing.getExpiresAt(); listing.setPendingEditId("edit");
        service.beginContentReview(listing,"owner-1");
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus());
        assertEquals(initial,listing.getOperationalStatus());
        assertEquals(initial,listing.getReviewOriginStatus());
        verifyNoInteractions(appointments);
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(initial==ListingStatus.EXPIRED?ListingStatus.PUBLISHED:initial,listing.getStatus());
        if(initial==ListingStatus.PUBLISHED || initial==ListingStatus.EXPIRED)
            assertTrue(listing.getExpiresAt().isAfter(originalExpiry));
        else assertEquals(originalExpiry,listing.getExpiresAt());
        assertNull(listing.getReviewOperationalStatus()); assertNull(listing.getReviewOriginStatus());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ListingStatus.class, names={"PUBLISHED","RESERVED","RENTED","HIDDEN","EXPIRED"})
    void rejectingContentDoesNotReleaseAnOccupiedRoom(ListingStatus initial) {
        Listing listing=publishedListingWithWindow(Duration.ofDays(10)); listing.setStatus(initial);
        service.beginContentReview(listing,"owner-1");
        service.finishContentReview(listing,"admin",false,"Cần sửa");
        assertEquals(initial==ListingStatus.PUBLISHED || initial==ListingStatus.EXPIRED?ListingStatus.REJECTED:initial,listing.getStatus());
        assertNull(listing.getReviewOperationalStatus()); assertNull(listing.getReviewOriginStatus());
    }

    @Test
    void signingDuringReviewKeepsPendingUntilAdminDecisionThenRestoresRented() {
        Listing listing=listing(ListingStatus.RESERVED); listing.setPendingEditId("edit");
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.beginContentReview(listing,"owner-1");
        service.markRentedByContract("listing-1","tenant");
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus());
        assertEquals(ListingStatus.RENTED,listing.getOperationalStatus());
        assertEquals(ListingStatus.RESERVED,listing.getReviewOriginStatus());
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(ListingStatus.RENTED,listing.getStatus());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"-1,EXPIRED","10,PUBLISHED"})
    void expiredHoldDuringReviewNeverRevivesAnExpiredPublication(int days, ListingStatus expected) {
        Listing listing=publishedListingWithWindow(Duration.ofDays(days)); listing.setStatus(ListingStatus.RESERVED);
        Instant expiresAt=listing.getExpiresAt();
        service.beginContentReview(listing,"owner-1");
        service.releaseReserved(listing,"SYSTEM","Hết giữ chỗ");
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus()); assertEquals(expected,listing.getOperationalStatus());
        // Resending content must not overwrite the original operational context.
        service.beginContentReview(listing,"owner-1"); assertEquals(ListingStatus.RESERVED,listing.getReviewOriginStatus());
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(expected,listing.getStatus()); assertEquals(expiresAt,listing.getExpiresAt());
    }

    @Test
    void handoverDuringReviewRemainsPendingAndApprovalKeepsTheNewPublicationWindow() {
        Listing listing=listing(ListingStatus.RENTED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.beginContentReview(listing,"owner-1");
        service.releaseRentedAfterTermination("listing-1","owner-1");
        Instant expiresAt=listing.getExpiresAt();
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus()); assertEquals(ListingStatus.PUBLISHED,listing.getOperationalStatus());
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(ListingStatus.PUBLISHED,listing.getStatus()); assertEquals(expiresAt,listing.getExpiresAt());
    }

    @Test
    void approvalRechecksThePublicationDeadlineEvenBeforeTheExpirySchedulerRuns() {
        Listing listing=publishedListingWithWindow(Duration.ofDays(1)); listing.setStatus(ListingStatus.RESERVED);
        service.beginContentReview(listing,"owner-1"); service.releaseReserved(listing,"SYSTEM",null);
        listing.setExpiresAt(Instant.now().minusSeconds(1));
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(ListingStatus.EXPIRED,listing.getStatus());
        verify(appointments).cancelActiveAppointmentsForListing("listing-1",ListingStatus.EXPIRED);
    }

    @Test
    void rejectionCancelsExistingViewingsOnlyAfterThePublicationDecision() {
        Listing listing=publishedListingWithWindow(Duration.ofDays(1));
        service.beginContentReview(listing,"owner-1"); verifyNoInteractions(appointments);
        service.finishContentReview(listing,"admin",false,"Cần sửa");
        verify(appointments).cancelActiveAppointmentsForListing("listing-1",ListingStatus.REJECTED);
    }

    @Test
    void publicationExpiryDuringReviewKeepsModerationPendingUntilDecision() {
        Listing listing=publishedListingWithWindow(Duration.ofDays(-1));
        service.beginContentReview(listing,"owner-1");
        when(listings.findPendingReviewsPastPublicationExpiry(any())).thenReturn(java.util.List.of(listing));
        assertEquals(1,service.expirePublishedListings(Instant.now()));
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus()); assertEquals(ListingStatus.EXPIRED,listing.getOperationalStatus());
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(ListingStatus.PUBLISHED,listing.getStatus()); assertTrue(listing.getExpiresAt().isAfter(Instant.now()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ListingStatus.class, names={"PUBLISHED","PENDING_REVIEW","RENTED_EXTERNALLY","HIDDEN","RESERVED"})
    void adminCannotManuallyReleaseReservedOrRentedRoom(ListingStatus target) {
        for(var from:java.util.List.of(ListingStatus.RESERVED,ListingStatus.RENTED)) {
            Listing listing=listing(from);
            when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
            assertThrows(AppException.class,()->service.changeByAdmin("admin","listing-1",target,"test"));
            assertEquals(from,listing.getStatus());
        }
        verify(listings,never()).save(any()); verifyNoInteractions(history);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=ListingStatus.class, names={"PUBLISHED","PENDING_REVIEW","RENTED_EXTERNALLY","REJECTED","HIDDEN"})
    void realRentalGuardBlocksPublicationEvenIfTheStoredStateWasChanged(ListingStatus target) {
        Listing listing=listing(target==ListingStatus.RENTED_EXTERNALLY?ListingStatus.PUBLISHED:ListingStatus.VIOLATION);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        when(listings.hasOccupiedRental("listing-1")).thenReturn(true);
        assertThrows(AppException.class,()->service.changeByAdmin("admin","listing-1",target,"test"));
        verify(listings,never()).save(any()); verifyNoInteractions(history);
    }

    @Test
    void ownerCannotRequeueAnOccupiedExternalRentalOrBypassVersionedModeration() {
        Listing listing=publishedListingWithWindow(Duration.ofDays(-1)); listing.setStatus(ListingStatus.RENTED_EXTERNALLY);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        when(listings.hasOccupiedRental("listing-1")).thenReturn(true);
        assertThrows(AppException.class,()->service.changeByOwner("owner-1","listing-1",ListingStatus.PENDING_REVIEW,null));
        listing.setStatus(ListingStatus.PUBLISHED); listing.setPendingEditId("edit");
        service.beginContentReview(listing,"owner-1"); clearInvocations(listings,history);
        assertThrows(AppException.class,()->service.changeByAdmin("admin","listing-1",ListingStatus.PUBLISHED,null));
        assertThrows(AppException.class,()->service.changeByOwner("owner-1","listing-1",ListingStatus.HIDDEN,null));
        verify(listings,never()).save(any()); verifyNoInteractions(history);
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
