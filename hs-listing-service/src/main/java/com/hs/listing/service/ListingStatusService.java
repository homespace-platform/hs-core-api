package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

@Service
public class ListingStatusService {
    /**
     * Owner may hide a published listing or mark it rented outside the platform.
     * {@link ListingStatus#RENTED} is reserved for the contract flow and is not owner-initiated.
     */
    private static final Map<ListingStatus, Set<ListingStatus>> OWNER_TRANSITIONS = Map.of(
            ListingStatus.PUBLISHED, Set.of(ListingStatus.HIDDEN, ListingStatus.RENTED_EXTERNALLY),
            ListingStatus.HIDDEN, Set.of(
                    ListingStatus.PUBLISHED, ListingStatus.RENTED_EXTERNALLY, ListingStatus.PENDING_REVIEW),
            ListingStatus.RENTED_EXTERNALLY, Set.of(ListingStatus.PUBLISHED),
            ListingStatus.EXPIRED, Set.of(ListingStatus.PENDING_REVIEW),
            ListingStatus.REJECTED, Set.of(ListingStatus.PENDING_REVIEW));

    private final ListingRepository listingRepository;
    private final ListingStatusHistoryRepository historyRepository;
    private final int publicationDurationDays;

    public ListingStatusService(
            ListingRepository listingRepository,
            ListingStatusHistoryRepository historyRepository,
            @Value("${listing.publication-duration-days:30}") int publicationDurationDays) {
        this.listingRepository = listingRepository;
        this.historyRepository = historyRepository;
        this.publicationDurationDays = publicationDurationDays > 0 ? publicationDurationDays : 30;
    }

    @Transactional
    public void applySubmission(Listing listing, ListingSubmissionAction action, String actorId,
                                ListingStatusActorType actorType) {
        if (listing.getStatus() == ListingStatus.VIOLATION && actorType == ListingStatusActorType.USER) {
            throw new AppException(ListingErrorCode.LISTING_LOCKED_BY_VIOLATION);
        }
        ListingStatus target = action == ListingSubmissionAction.SAVE_DRAFT
                ? ListingStatus.DRAFT : ListingStatus.PENDING_REVIEW;
        change(listing, target, null, actorId, actorType, false);
    }

    @Transactional
    public Listing changeByOwner(String ownerId, String listingId, ListingStatus target, String note) {
        Listing listing = requireOwned(ownerId, listingId);
        if (listing.getStatus() == ListingStatus.VIOLATION) {
            throw new AppException(ListingErrorCode.LISTING_LOCKED_BY_VIOLATION);
        }
        if (listing.getStatus() == ListingStatus.RENTED) {
            throw new AppException(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT);
        }
        if (target == ListingStatus.RENTED) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        if (!OWNER_TRANSITIONS.getOrDefault(listing.getStatus(), Set.of()).contains(target)) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        if (target == ListingStatus.PUBLISHED && !hasRemainingPublicationWindow(listing)) {
            throw new AppException(ListingErrorCode.LISTING_PUBLICATION_WINDOW_ENDED);
        }
        if (target == ListingStatus.PENDING_REVIEW
                && listing.getStatus() == ListingStatus.HIDDEN
                && hasRemainingPublicationWindow(listing)) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        change(listing, target, normalizeReason(note), ownerId, ListingStatusActorType.USER, false);
        return listing;
    }

    private boolean hasRemainingPublicationWindow(Listing listing) {
        return listing.getExpiresAt() != null && listing.getExpiresAt().isAfter(Instant.now());
    }

    @Transactional
    public Listing changeByAdmin(String adminId, String listingId, ListingStatus target, String reason) {
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        validateAdminTransition(listing.getStatus(), target);
        if (Set.of(ListingStatus.REJECTED, ListingStatus.HIDDEN, ListingStatus.VIOLATION).contains(target)
                && (reason == null || reason.isBlank())) {
            throw new AppException(ListingErrorCode.MODERATION_REASON_REQUIRED);
        }
        change(listing, target, normalizeReason(reason), adminId, ListingStatusActorType.ADMIN, true);
        return listing;
    }

    @Transactional
    public int expirePublishedListings(Instant now) {
        var expired = listingRepository.findAllByStatusAndActiveTrueAndExpiresAtLessThanEqual(
                ListingStatus.PUBLISHED, now);
        expired.forEach(listing -> change(
                listing, ListingStatus.EXPIRED, "Publication period expired", "SYSTEM",
                ListingStatusActorType.SYSTEM, true));
        return expired.size();
    }

    private Listing requireOwned(String ownerId, String listingId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ListingErrorCode.LISTING_AUTHENTICATION_REQUIRED);
        }
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        if (!ownerId.equals(listing.getOwnerId())) {
            throw new AppException(ListingErrorCode.LISTING_FORBIDDEN);
        }
        return listing;
    }

    private void validateAdminTransition(ListingStatus from, ListingStatus to) {
        if (from == to) throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        boolean allowed = switch (to) {
            case PUBLISHED -> from == ListingStatus.PENDING_REVIEW || from == ListingStatus.VIOLATION;
            case REJECTED -> from == ListingStatus.PENDING_REVIEW;
            case RENTED -> from == ListingStatus.PUBLISHED || from == ListingStatus.RENTED_EXTERNALLY;
            case RENTED_EXTERNALLY -> from == ListingStatus.PUBLISHED || from == ListingStatus.RENTED;
            case EXPIRED -> from == ListingStatus.PUBLISHED;
            case VIOLATION -> from != ListingStatus.VIOLATION;
            case HIDDEN -> from != ListingStatus.HIDDEN;
            case PENDING_REVIEW -> Set.of(
                    ListingStatus.DRAFT, ListingStatus.REJECTED, ListingStatus.EXPIRED,
                    ListingStatus.HIDDEN, ListingStatus.RENTED, ListingStatus.RENTED_EXTERNALLY,
                    ListingStatus.VIOLATION).contains(from);
            case DRAFT -> false;
        };
        if (!allowed) throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
    }

    private void change(Listing listing, ListingStatus target, String reason, String actorId,
                        ListingStatusActorType actorType, boolean validateExisting) {
        ListingStatus previous = listing.getStatus();
        if (previous == target) {
            if (target == ListingStatus.PENDING_REVIEW) {
                Instant now = Instant.now();
                listing.setSubmittedAt(now);
                listing.setStatusChangedAt(now);
                listing.setStatusChangedBy(actorId);
                listingRepository.save(listing);
            }
            return;
        }
        if (validateExisting && previous == null) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        Instant now = Instant.now();
        listing.setStatus(target);
        listing.setStatusReason(reason);
        listing.setStatusChangedAt(now);
        listing.setStatusChangedBy(actorId);
        if (target == ListingStatus.PENDING_REVIEW) listing.setSubmittedAt(now);
        boolean startsNewPublicationWindow = target == ListingStatus.PUBLISHED
                && (previous == ListingStatus.PENDING_REVIEW
                || previous == ListingStatus.VIOLATION
                || listing.getPublishedAt() == null);
        if (startsNewPublicationWindow) {
            listing.setPublishedAt(now);
            listing.setExpiresAt(now.plus(publicationDurationDays, ChronoUnit.DAYS));
        }
        listingRepository.save(listing);
        historyRepository.save(ListingStatusHistory.builder()
                .listingId(listing.getId())
                .fromStatus(previous)
                .toStatus(target)
                .reason(reason)
                .changedBy(actorId)
                .changedByType(actorType)
                .build());
    }

    private String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.trim();
    }
}
