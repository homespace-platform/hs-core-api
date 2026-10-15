package com.hs.listing.service.ai;

import com.hs.listing.dto.ai.AiAppointmentDtos.Actions;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mirror of the checks {@code ViewingAppointmentService} performs before each action, in the same order, so
 * the assistant can say whether an action is currently possible and why not. Reading this performs nothing. The
 * time validation of a newly proposed slot happens when the user submits it, so it is not predicted here.
 * A parity test keeps this class and the real service in agreement.
 */
public final class AppointmentActionRules {
    public static final String REQUEST_RESCHEDULE = "REQUEST_RESCHEDULE";
    public static final String CANCEL = "CANCEL";
    public static final String APPROVE = "APPROVE";
    public static final String REJECT = "REJECT";
    public static final String APPROVE_RESCHEDULE = "APPROVE_RESCHEDULE";
    public static final String REJECT_RESCHEDULE = "REJECT_RESCHEDULE";
    public static final String COMPLETE = "COMPLETE";

    private AppointmentActionRules() {}

    private static boolean pendingOrConfirmed(AppointmentStatus s) {
        return s == AppointmentStatus.PENDING || s == AppointmentStatus.CONFIRMED;
    }

    public static List<String> renterBlockers(String action, AppointmentStatus status, boolean rescheduleRequested,
                                              ListingStatus listingStatus) {
        List<String> reasons = new ArrayList<>();
        switch (action) {
            case REQUEST_RESCHEDULE -> {
                if (!pendingOrConfirmed(status)) reasons.add("STATUS_NOT_PENDING_OR_CONFIRMED");
                else if (rescheduleRequested) reasons.add("RESCHEDULE_ALREADY_REQUESTED");
                else if (listingStatus != ListingStatus.PUBLISHED) reasons.add("LISTING_NOT_PUBLISHED");
            }
            case CANCEL -> {
                if (!pendingOrConfirmed(status)) reasons.add("STATUS_NOT_PENDING_OR_CONFIRMED");
            }
            default -> throw new IllegalArgumentException(action);
        }
        return reasons;
    }

    public static List<String> ownerBlockers(String action, AppointmentStatus status, boolean rescheduleRequested,
                                             ListingStatus listingStatus) {
        List<String> reasons = new ArrayList<>();
        switch (action) {
            case APPROVE -> {
                if (status != AppointmentStatus.PENDING) reasons.add("STATUS_NOT_PENDING");
                else if (listingStatus != ListingStatus.PUBLISHED) reasons.add("LISTING_NOT_PUBLISHED");
            }
            case REJECT -> {
                if (status != AppointmentStatus.PENDING) reasons.add("STATUS_NOT_PENDING");
            }
            case APPROVE_RESCHEDULE, REJECT_RESCHEDULE -> {
                if (!rescheduleRequested) reasons.add("NO_PENDING_RESCHEDULE_REQUEST");
            }
            case CANCEL -> {
                if (!pendingOrConfirmed(status)) reasons.add("STATUS_NOT_PENDING_OR_CONFIRMED");
            }
            case COMPLETE -> {
                if (status != AppointmentStatus.CONFIRMED) reasons.add("STATUS_NOT_CONFIRMED");
            }
            default -> throw new IllegalArgumentException(action);
        }
        return reasons;
    }

    public static Actions forRole(boolean owner, AppointmentStatus status, boolean rescheduleRequested,
                                  ListingStatus listingStatus) {
        List<String> actions = owner
                ? List.of(APPROVE, REJECT, APPROVE_RESCHEDULE, REJECT_RESCHEDULE, CANCEL, COMPLETE)
                : List.of(REQUEST_RESCHEDULE, CANCEL);
        List<String> available = new ArrayList<>();
        Map<String, List<String>> blocked = new LinkedHashMap<>();
        for (String action : actions) {
            List<String> reasons = owner ? ownerBlockers(action, status, rescheduleRequested, listingStatus)
                    : renterBlockers(action, status, rescheduleRequested, listingStatus);
            if (reasons.isEmpty()) available.add(action);
            else blocked.put(action, reasons);
        }
        return new Actions(available, blocked);
    }
}
