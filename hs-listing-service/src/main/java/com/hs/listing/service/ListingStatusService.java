package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingStatusHistory;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.ListingNotificationWriter;
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
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationEventPublisher nearbyEvents;
    /**
     * Owner may hide a published listing or mark it rented outside the platform.
     * {@link ListingStatus#RENTED} is reserved for the contract flow and is not owner-initiated.
     */
    private static final Map<ListingStatus, Set<ListingStatus>> OWNER_TRANSITIONS = Map.of(
            ListingStatus.PUBLISHED, Set.of(ListingStatus.HIDDEN, ListingStatus.RENTED_EXTERNALLY),
            ListingStatus.HIDDEN, Set.of(
                    ListingStatus.PUBLISHED, ListingStatus.RENTED_EXTERNALLY, ListingStatus.PENDING_REVIEW),
            ListingStatus.RENTED_EXTERNALLY, Set.of(ListingStatus.PUBLISHED, ListingStatus.PENDING_REVIEW),
            ListingStatus.EXPIRED, Set.of(ListingStatus.PENDING_REVIEW),
            ListingStatus.REJECTED, Set.of(ListingStatus.PENDING_REVIEW));

    private final ListingRepository listingRepository;
    private final ListingStatusHistoryRepository historyRepository;
    private final ViewingAppointmentService appointmentService;
    @org.springframework.beans.factory.annotation.Autowired
    private com.hs.listing.membership.MembershipService memberships;
    @org.springframework.beans.factory.annotation.Autowired
    private ListingNotificationWriter notifications;
    private final int publicationDurationDays;

    public ListingStatusService(
            ListingRepository listingRepository,
            ListingStatusHistoryRepository historyRepository,
            ViewingAppointmentService appointmentService,
            @Value("${listing.publication-duration-days}") int publicationDurationDays) {
        this.listingRepository = listingRepository;
        this.historyRepository = historyRepository;
        this.appointmentService = appointmentService;
        if (publicationDurationDays <= 0) {
            throw new IllegalArgumentException("listing.publication-duration-days must be positive");
        }
        this.publicationDurationDays = publicationDurationDays;
    }

    @Transactional
    public void applySubmission(Listing listing, ListingSubmissionAction action, String actorId,
                                ListingStatusActorType actorType) {
        if (listing.getStatus() == ListingStatus.RENTED) {
            throw new AppException(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT);
        }
        if (listing.getStatus() == ListingStatus.RENTED_EXTERNALLY) {
            throw new AppException(ListingErrorCode.LISTING_RENTED_EXTERNALLY);
        }
        if (listing.getStatus() == ListingStatus.VIOLATION && actorType == ListingStatusActorType.USER) {
            throw new AppException(ListingErrorCode.LISTING_LOCKED_BY_VIOLATION);
        }
        ListingStatus target = action == ListingSubmissionAction.SAVE_DRAFT
                ? ListingStatus.DRAFT : ListingStatus.PENDING_REVIEW;
        if (target == ListingStatus.PENDING_REVIEW && listing.getPublicationReservation() == null
                && !hasRemainingPublicationWindow(listing) && memberships != null)
            memberships.reserve(listing, actorType == ListingStatusActorType.ADMIN);
        if (target == ListingStatus.DRAFT && listing.getPublicationReservation() != null && memberships != null)
            memberships.finish(listing, false);
        change(listing, target, null, actorId, actorType, false);
    }

    @Transactional
    public Listing changeByOwner(String ownerId, String listingId, ListingStatus target, String note) {
        Listing listing = requireOwned(ownerId, listingId);
        if (listing.getPendingEditId() != null) throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION,
                "Tin đang chờ duyệt nội dung; hãy xử lý bản gửi hiện tại trước.");
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
        if (target == ListingStatus.PENDING_REVIEW && listing.getStatus() == ListingStatus.RENTED_EXTERNALLY
                && hasRemainingPublicationWindow(listing)) throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        if (target == ListingStatus.PUBLISHED || target == ListingStatus.PENDING_REVIEW || target == ListingStatus.RENTED_EXTERNALLY)
            requireUnoccupied(listing);
        if (target == ListingStatus.PENDING_REVIEW
                && listing.getStatus() == ListingStatus.HIDDEN
                && hasRemainingPublicationWindow(listing)) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        if (target == ListingStatus.PENDING_REVIEW && !hasRemainingPublicationWindow(listing)
                && memberships != null) memberships.reserve(listing, false);
        change(listing, target, normalizeReason(note), ownerId, ListingStatusActorType.USER, false);
        return listing;
    }

    private boolean hasRemainingPublicationWindow(Listing listing) {
        return listing.getExpiresAt() != null && listing.getExpiresAt().isAfter(Instant.now());
    }

    @Transactional
    public Listing changeByAdmin(String adminId, String listingId, ListingStatus target, String reason) {
        Listing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        if (listing.getPendingEditId() != null && target != ListingStatus.VIOLATION)
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION,"Hãy duyệt đúng phiên bản nội dung đã gửi.");
        if (target != ListingStatus.VIOLATION && hasProtectedRental(listing))
            throw new AppException(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT);
        validateAdminTransition(listing.getStatus(), target);
        if (Set.of(ListingStatus.REJECTED, ListingStatus.HIDDEN, ListingStatus.VIOLATION).contains(target)
                && (reason == null || reason.isBlank())) {
            throw new AppException(ListingErrorCode.MODERATION_REASON_REQUIRED);
        }
        if (target == ListingStatus.PENDING_REVIEW && !hasRemainingPublicationWindow(listing)
                && memberships != null) memberships.reserve(listing, true);
        if (target == ListingStatus.PUBLISHED && listing.getPublicationReservation() == null
                && !hasRemainingPublicationWindow(listing) && memberships != null) memberships.reserve(listing, true);
        if ((target == ListingStatus.PUBLISHED || target == ListingStatus.REJECTED)
                && listing.getPublicationReservation() != null && memberships != null)
            memberships.finish(listing, target == ListingStatus.PUBLISHED);
        if ((target == ListingStatus.VIOLATION || target == ListingStatus.HIDDEN)
                && listing.getPublicationReservation() != null && memberships != null)
            memberships.finish(listing, false);
        change(listing, target, normalizeReason(reason), adminId, ListingStatusActorType.ADMIN, true);
        if (target == ListingStatus.VIOLATION) {
            listing.setPendingEditId(null); listing.setPendingEditPayload(null);
            listing.setReviewOperationalStatus(null); listing.setReviewOriginStatus(null);
        }
        // Decision-level event, after validation and the state change; a VIOLATION that discards a pending edit
        // is only a status change, never an edit rejection. Joins the caller's transaction (MANDATORY).
        var event = switch (target) {
            case PUBLISHED -> ListingNotificationWriter.Event.LISTING_APPROVED;
            case REJECTED -> ListingNotificationWriter.Event.LISTING_REJECTED;
            case EXPIRED -> ListingNotificationWriter.Event.LISTING_EXPIRED;
            default -> ListingNotificationWriter.Event.LISTING_STATUS_CHANGED;
        };
        notifications.enqueue(listing, adminId, event, null,
                event == ListingNotificationWriter.Event.LISTING_STATUS_CHANGED ? target : null);
        return listing;
    }

    @Transactional
    public int expirePublishedListings(Instant now) {
        var expired = new java.util.ArrayList<>(listingRepository.findAllByStatusAndActiveTrueAndExpiresAtLessThanEqual(
                ListingStatus.PUBLISHED, now));
        expired.addAll(listingRepository.findPendingReviewsPastPublicationExpiry(now));
        expired.forEach(listing -> {
            changeOperational(listing, ListingStatus.EXPIRED, "Publication period expired", "SYSTEM",
                    ListingStatusActorType.SYSTEM, true);
            notifications.enqueue(listing, "SYSTEM", ListingNotificationWriter.Event.LISTING_EXPIRED);
        });
        return expired.size();
    }

    private Listing requireOwned(String ownerId, String listingId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ListingErrorCode.LISTING_AUTHENTICATION_REQUIRED);
        }
        Listing listing = listingRepository.findByIdForUpdate(listingId)
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
            case RENTED -> false; // RENTED is reserved strictly for contract execution flow
            case RENTED_EXTERNALLY -> from == ListingStatus.PUBLISHED;
            case EXPIRED -> from == ListingStatus.PUBLISHED;
            case VIOLATION -> from != ListingStatus.VIOLATION;
            case HIDDEN -> from != ListingStatus.HIDDEN;
            case RESERVED -> false;
            case PENDING_REVIEW -> Set.of(
                    ListingStatus.DRAFT, ListingStatus.REJECTED, ListingStatus.EXPIRED,
                    ListingStatus.HIDDEN, ListingStatus.RENTED_EXTERNALLY,
                    ListingStatus.VIOLATION).contains(from);
            case DRAFT -> false;
        };
        if (!allowed) throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
    }

    @Transactional
    public void markReserved(Listing listing, String actorId) {
        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        change(listing, ListingStatus.RESERVED, "Chấp thuận yêu cầu giữ chỗ thuê nhà", actorId,
                ListingStatusActorType.USER, true);
    }

    @Transactional
    public void releaseReserved(Listing listing, String actorId, String reason) {
        if (listing.getOperationalStatus() != ListingStatus.RESERVED) {
            return;
        }
        ListingStatus restored = hasRemainingPublicationWindow(listing) ? ListingStatus.PUBLISHED : ListingStatus.EXPIRED;
        changeOperational(listing, restored, reason != null ? reason : "Hết hạn giữ chỗ hoặc hủy giữ chỗ", actorId,
                ListingStatusActorType.SYSTEM, false);
    }

    @Transactional
    public void markRentedByContract(String listingId, String actorId) {
        Listing listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        if (listing.getOperationalStatus() != ListingStatus.RESERVED) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        changeOperational(listing, ListingStatus.RENTED, "Hợp đồng thuê đã được thanh toán và ký", actorId,
                ListingStatusActorType.USER, true);
    }

    /** Re-publish only after the contract service has completed a verified handover. */
    @Transactional
    public void releaseRentedAfterTermination(String listingId, String ownerId) {
        Listing listing = requireOwned(ownerId, listingId);
        if (listing.getOperationalStatus() != ListingStatus.RENTED)
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        changeOperational(listing, ListingStatus.PUBLISHED, "Đã chấm dứt hợp đồng và xác nhận bàn giao phòng",
                ownerId, ListingStatusActorType.SYSTEM, true);
        if (memberships != null) {
            memberships.reserve(listing, false);
            memberships.finish(listing, true);
        }
        Instant now = Instant.now();
        listing.setPublishedAt(now);
        listing.setExpiresAt(now.plus(publicationDurationDays, ChronoUnit.DAYS));
        listing.setAvailableFrom(java.time.LocalDate.now());
        listingRepository.save(listing);
    }

    private void change(Listing listing, ListingStatus target, String reason, String actorId,
                        ListingStatusActorType actorType, boolean validateExisting) {
        change(listing,target,reason,actorId,actorType,validateExisting,null);
    }

    private void change(Listing listing, ListingStatus target, String reason, String actorId,
                        ListingStatusActorType actorType, boolean validateExisting, Boolean renewPublication) {
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
                && (renewPublication != null ? renewPublication : (previous == ListingStatus.PENDING_REVIEW
                || previous == ListingStatus.VIOLATION
                || listing.getPublishedAt() == null));
        if (startsNewPublicationWindow) {
            listing.setPublishedAt(now);
            listing.setExpiresAt(now.plus(publicationDurationDays, ChronoUnit.DAYS));
        }
        listingRepository.save(listing);

        if (nearbyEvents != null && target == ListingStatus.PUBLISHED)
            nearbyEvents.publishEvent(new com.hs.listing.nearby.NearbyTypes.ScanChanged(listing.getId()));

        cancelAppointmentsIfUnavailable(listing,previous,target,actorId);

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

    public boolean hasProtectedRental(Listing listing) {
        return listing.getOperationalStatus() == ListingStatus.RESERVED || listing.getOperationalStatus() == ListingStatus.RENTED
                || listingRepository.hasOccupiedRental(listing.getId());
    }

    private void requireUnoccupied(Listing listing) {
        if (hasProtectedRental(listing)) throw new AppException(ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT,
                "Tin còn hồ sơ giữ chỗ hoặc hợp đồng chưa bàn giao; không thể chuyển sang đăng lại/công khai bằng thao tác quản trị.");
    }

    @Transactional
    public void beginContentReview(Listing listing, String actorId) {
        ListingStatus previous = listing.getStatus();
        if (listing.getReviewOperationalStatus() == null) {
            listing.setReviewOperationalStatus(previous); listing.setReviewOriginStatus(previous);
        }
        listing.setStatus(ListingStatus.PENDING_REVIEW);
        listing.setStatusReason(null);
        listing.setSubmittedAt(Instant.now()); listing.setStatusChangedAt(listing.getSubmittedAt()); listing.setStatusChangedBy(actorId);
        listingRepository.save(listing);
        historyRepository.save(ListingStatusHistory.builder().listingId(listing.getId()).fromStatus(previous)
                .toStatus(ListingStatus.PENDING_REVIEW).reason("Gửi chỉnh sửa nội dung để duyệt")
                .changedBy(actorId).changedByType(ListingStatusActorType.USER).build());
        // Moderation does not cancel existing viewings, holds, payments or contracts.
    }

    @Transactional
    public void finishContentReview(Listing listing, String actorId, boolean approve, String reason) {
        ListingStatus operational = listing.getOperationalStatus();
        ListingStatus origin = listing.getReviewOriginStatus() == null ? operational : listing.getReviewOriginStatus();
        ListingStatus target = operational;
        boolean publicationReview = origin == ListingStatus.PUBLISHED || origin == ListingStatus.EXPIRED;
        if (target == ListingStatus.PUBLISHED && listing.getExpiresAt() != null && !hasRemainingPublicationWindow(listing))
            target = ListingStatus.EXPIRED;
        if (publicationReview && (operational == ListingStatus.PUBLISHED || operational == ListingStatus.EXPIRED))
            target = approve ? (hasRemainingPublicationWindow(listing) ? ListingStatus.PUBLISHED : ListingStatus.EXPIRED)
                    : ListingStatus.REJECTED;
        if (target == ListingStatus.PUBLISHED) requireUnoccupied(listing);
        listing.setReviewOperationalStatus(null); listing.setReviewOriginStatus(null);
        change(listing, target, (approve ? "Đã duyệt nội dung " : "Từ chối nội dung ") + listing.getPendingEditId()
                + (reason == null || reason.isBlank() ? "" : ": " + reason.trim()), actorId, ListingStatusActorType.ADMIN, false,
                false);
        cancelAppointmentsIfUnavailable(listing,operational,target,actorId);
    }

    private void changeOperational(Listing listing, ListingStatus target, String reason, String actorId,
                                   ListingStatusActorType actorType, boolean validateExisting) {
        if (listing.getStatus() != ListingStatus.PENDING_REVIEW || listing.getReviewOperationalStatus() == null) {
            change(listing,target,reason,actorId,actorType,validateExisting); return;
        }
        ListingStatus previous = listing.getReviewOperationalStatus();
        listing.setReviewOperationalStatus(target); listingRepository.save(listing);
        historyRepository.save(ListingStatusHistory.builder().listingId(listing.getId()).fromStatus(previous).toStatus(target)
                .reason("Trong thời gian chờ duyệt: " + reason).changedBy(actorId).changedByType(actorType).build());
        cancelAppointmentsIfUnavailable(listing,previous,target,actorId);
    }

    private void cancelAppointmentsIfUnavailable(Listing listing, ListingStatus previous, ListingStatus target, String actorId) {
        if (target != ListingStatus.PUBLISHED && target != ListingStatus.RESERVED
                && (previous == ListingStatus.PUBLISHED || previous == ListingStatus.RESERVED)) {
            appointmentService.cancelActiveAppointmentsForListing(listing.getId(), target, actorId);
        }
    }
}
