package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.ListingNotificationOutbox;
import com.hs.listing.notification.ListingNotificationOutboxRepository;
import com.hs.listing.notification.ListingNotificationWriter;
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
    private final ListingNotificationOutboxRepository outbox = mock(ListingNotificationOutboxRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final ListingStatusHistoryRepository history = mock(ListingStatusHistoryRepository.class);
    private final ViewingAppointmentService appointments = mock(ViewingAppointmentService.class);
    private final ListingStatusService service = new ListingStatusService(
            listings, history, appointments, 30);

    @org.junit.jupiter.api.BeforeEach
    void notifications() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "notifications",
                new ListingNotificationWriter(outbox, "test.notifications"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "PENDING_REVIEW,PUBLISHED,LISTING_APPROVED", "VIOLATION,PUBLISHED,LISTING_APPROVED",
            "PENDING_REVIEW,REJECTED,LISTING_REJECTED", "PUBLISHED,EXPIRED,LISTING_EXPIRED",
            "PUBLISHED,RENTED_EXTERNALLY,LISTING_STATUS_CHANGED",
            "DRAFT,PENDING_REVIEW,LISTING_STATUS_CHANGED", "REJECTED,PENDING_REVIEW,LISTING_STATUS_CHANGED",
            "EXPIRED,PENDING_REVIEW,LISTING_STATUS_CHANGED", "HIDDEN,PENDING_REVIEW,LISTING_STATUS_CHANGED",
            "RENTED_EXTERNALLY,PENDING_REVIEW,LISTING_STATUS_CHANGED", "VIOLATION,PENDING_REVIEW,LISTING_STATUS_CHANGED",
            "DRAFT,HIDDEN,LISTING_STATUS_CHANGED", "PENDING_REVIEW,HIDDEN,LISTING_STATUS_CHANGED",
            "PUBLISHED,HIDDEN,LISTING_STATUS_CHANGED", "REJECTED,HIDDEN,LISTING_STATUS_CHANGED",
            "EXPIRED,HIDDEN,LISTING_STATUS_CHANGED", "RENTED_EXTERNALLY,HIDDEN,LISTING_STATUS_CHANGED",
            "VIOLATION,HIDDEN,LISTING_STATUS_CHANGED",
            "DRAFT,VIOLATION,LISTING_STATUS_CHANGED", "PENDING_REVIEW,VIOLATION,LISTING_STATUS_CHANGED",
            "PUBLISHED,VIOLATION,LISTING_STATUS_CHANGED", "RESERVED,VIOLATION,LISTING_STATUS_CHANGED",
            "RENTED,VIOLATION,LISTING_STATUS_CHANGED", "RENTED_EXTERNALLY,VIOLATION,LISTING_STATUS_CHANGED",
            "EXPIRED,VIOLATION,LISTING_STATUS_CHANGED", "REJECTED,VIOLATION,LISTING_STATUS_CHANGED",
            "HIDDEN,VIOLATION,LISTING_STATUS_CHANGED"})
    void adminDecisionsNotifyThePersistedOwner(ListingStatus from, ListingStatus to, String eventType) {
        Listing listing = listing(from);
        when(listings.findByIdForUpdate(listing.getId())).thenReturn(Optional.of(listing));
        service.changeByAdmin("reviewer", listing.getId(), to, "  reason  ");
        var row = ArgumentCaptor.forClass(ListingNotificationOutbox.class);
        verify(outbox).save(row.capture());
        var event = tools.jackson.databind.json.JsonMapper.builder().build().readTree(row.getValue().getPayload());
        assertEquals(eventType, event.get("eventType").asText());
        assertEquals("owner-1", event.get("recipients").get(0).asText());
        assertEquals("listing-1", event.get("data").get("listingId").asText());
        assertEquals(eventType.equals("LISTING_STATUS_CHANGED"), event.get("data").has("status"));
        if (eventType.equals("LISTING_STATUS_CHANGED")) assertEquals(to.name(), event.get("data").get("status").asText());
        assertFalse(event.get("data").has("reason"));
        assertFalse(event.get("data").has("editId"));
        assertEquals(to, listing.getStatus());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ListingStatus.class)
    void repeatedAdminStatusDecisionsNeverNotify(ListingStatus status) {
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing(status)));
        assertThrows(AppException.class, () -> service.changeByAdmin("admin", "listing-1", status, "reason"));
        verifyNoInteractions(outbox);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ListingStatus.class, names = {"REJECTED", "HIDDEN", "VIOLATION"})
    void moderationWithoutAReasonNeverNotifies(ListingStatus target) {
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing(ListingStatus.PENDING_REVIEW)));
        assertThrows(AppException.class, () -> service.changeByAdmin("admin", "listing-1", target, "  "));
        verifyNoInteractions(outbox);
    }

    @Test
    void violationDiscardsPendingEditWithoutAnEditRejectionEvent() {
        Listing listing = listing(ListingStatus.PENDING_REVIEW);
        listing.setPendingEditId("pending-edit"); listing.setPendingEditPayload("unreviewed");
        listing.setReviewOperationalStatus(ListingStatus.RENTED); listing.setReviewOriginStatus(ListingStatus.RESERVED);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.changeByAdmin("admin", "listing-1", ListingStatus.VIOLATION, "reason");
        assertNull(listing.getPendingEditId()); assertNull(listing.getReviewOperationalStatus());
        var row = ArgumentCaptor.forClass(ListingNotificationOutbox.class);
        verify(outbox).save(row.capture());
        assertTrue(row.getValue().getPayload().contains("LISTING_STATUS_CHANGED"));
        assertFalse(row.getValue().getPayload().contains("LISTING_EDIT_REJECTED"));
    }

    @Test
    void ownerActionsAndInternalRentalTransitionsDoNotCreateListingNotifications() {
        Listing listing = publishedListingWithWindow(Duration.ofDays(3));
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.changeByOwner("owner-1", "listing-1", ListingStatus.HIDDEN, null);
        service.changeByOwner("owner-1", "listing-1", ListingStatus.PUBLISHED, null);
        service.markReserved(listing, "owner-1");
        service.releaseReserved(listing, "tenant", null);
        service.markReserved(listing, "owner-1");
        service.markRentedByContract("listing-1", "tenant");
        service.releaseRentedAfterTermination("listing-1", "owner-1");
        service.applySubmission(listing(ListingStatus.DRAFT), ListingSubmissionAction.SUBMIT_FOR_REVIEW, "owner-1", ListingStatusActorType.USER);
        verifyNoInteractions(outbox);
    }

    @Test
    void selfAdminDecisionChangesStateWithoutNotifyingTheActor() {
        Listing listing = listing(ListingStatus.PENDING_REVIEW);
        when(listings.findByIdForUpdate("listing-1")).thenReturn(Optional.of(listing));
        service.changeByAdmin("owner-1", "listing-1", ListingStatus.PUBLISHED, null);
        assertEquals(ListingStatus.PUBLISHED, listing.getStatus());
        verifyNoInteractions(outbox);
    }

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
        assertEquals(originalExpiry,listing.getExpiresAt());
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
        verify(appointments).cancelActiveAppointmentsForListing("listing-1",ListingStatus.EXPIRED,"admin");
    }

    @Test
    void rejectionCancelsExistingViewingsOnlyAfterThePublicationDecision() {
        Listing listing=publishedListingWithWindow(Duration.ofDays(1));
        service.beginContentReview(listing,"owner-1"); verifyNoInteractions(appointments);
        service.finishContentReview(listing,"admin",false,"Cần sửa");
        verify(appointments).cancelActiveAppointmentsForListing("listing-1",ListingStatus.REJECTED,"admin");
    }

    @Test
    void publicationExpiryDuringReviewKeepsModerationPendingUntilDecision() {
        Listing listing=publishedListingWithWindow(Duration.ofDays(-1));
        service.beginContentReview(listing,"owner-1");
        when(listings.findPendingReviewsPastPublicationExpiry(any())).thenReturn(java.util.List.of(listing));
        assertEquals(1,service.expirePublishedListings(Instant.now()));
        assertEquals(ListingStatus.PENDING_REVIEW,listing.getStatus()); assertEquals(ListingStatus.EXPIRED,listing.getOperationalStatus());
        service.finishContentReview(listing,"admin",true,null);
        assertEquals(ListingStatus.EXPIRED,listing.getStatus());
        assertTrue(listing.getExpiresAt().isBefore(Instant.now()));
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
