package com.hs.listing.service.ai;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.dto.ai.AiAppointmentDtos.Actions;
import com.hs.listing.dto.request.CancelAppointmentRequest;
import com.hs.listing.dto.request.RejectAppointmentRequest;
import com.hs.listing.dto.request.RescheduleAppointmentRequest;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ViewingAppointmentRepository;
import com.hs.listing.service.ViewingAppointmentService;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.AddressRepository;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The "what can I do with this appointment?" hints must never promise something the real service would refuse.
 * For every status x reschedule-flag x listing-status combination, every action is actually attempted on the real
 * {@link ViewingAppointmentService} (mocked persistence) and compared with {@link AppointmentActionRules}.
 */
class AppointmentActionRulesParityTest {
    static final String OWNER = "owner", RENTER = "renter";

    @Test
    void rulesAgreeWithTheRealServiceForEveryCombination() {
        for (AppointmentStatus status : AppointmentStatus.values()) {
            for (boolean reschedule : new boolean[]{false, true}) {
                for (ListingStatus listingStatus : new ListingStatus[]{ListingStatus.PUBLISHED, ListingStatus.HIDDEN}) {
                    check(true, status, reschedule, listingStatus);
                    check(false, status, reschedule, listingStatus);
                }
            }
        }
    }

    private void check(boolean owner, AppointmentStatus status, boolean reschedule, ListingStatus listingStatus) {
        Actions predicted = AppointmentActionRules.forRole(owner, status, reschedule, listingStatus);
        java.util.Map<String, Function<ViewingAppointmentService, Object>> attempts = new java.util.LinkedHashMap<>();
        String id = "apt";
        if (owner) {
            attempts.put(AppointmentActionRules.APPROVE, s -> s.approveAppointment(OWNER, id, null));
            attempts.put(AppointmentActionRules.REJECT, s -> s.rejectAppointment(OWNER, id, new RejectAppointmentRequest("x")));
            attempts.put(AppointmentActionRules.APPROVE_RESCHEDULE, s -> s.approveReschedule(OWNER, id));
            attempts.put(AppointmentActionRules.REJECT_RESCHEDULE, s -> s.rejectReschedule(OWNER, id, new RejectAppointmentRequest("x")));
            attempts.put(AppointmentActionRules.CANCEL, s -> s.cancelByOwner(OWNER, id, new CancelAppointmentRequest("x")));
            attempts.put(AppointmentActionRules.COMPLETE, s -> s.completeAppointment(OWNER, id));
        } else {
            LocalDate day = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(3);
            attempts.put(AppointmentActionRules.REQUEST_RESCHEDULE, s -> s.requestReschedule(RENTER, id,
                    new RescheduleAppointmentRequest(day, LocalTime.of(9, 0), LocalTime.of(10, 0), "x")));
            attempts.put(AppointmentActionRules.CANCEL, s -> s.cancelByRenter(RENTER, id, new CancelAppointmentRequest("x")));
        }
        for (var entry : attempts.entrySet()) {
            // a fresh appointment and service per attempt: actions mutate the entity they succeed on
            ViewingAppointmentService service = serviceFor(appointment(status, reschedule, listingStatus));
            boolean allowed;
            try {
                entry.getValue().apply(service);
                allowed = true;
            } catch (AppException refused) {
                allowed = false;
            }
            assertEquals(allowed, predicted.available().contains(entry.getKey()),
                    () -> "owner=%s status=%s reschedule=%s listing=%s action=%s".formatted(
                            owner, status, reschedule, listingStatus, entry.getKey()));
            assertEquals(!allowed, predicted.blocked().containsKey(entry.getKey()));
        }
    }

    private static ViewingAppointment appointment(AppointmentStatus status, boolean reschedule, ListingStatus listingStatus) {
        Listing listing = Listing.builder().id("listing").ownerId(OWNER).status(listingStatus)
                .viewingDays(Set.of(DayOfWeek.values())).viewingSlots(Set.of(ViewingSlot.values())).build();
        listing.setActive(true);
        ViewingAppointment a = ViewingAppointment.builder().id("apt").listing(listing).ownerId(OWNER).renterId(RENTER)
                .renterName("r").renterPhone("0").status(status).rescheduleRequested(reschedule)
                .appointmentDate(LocalDate.now().plusDays(1)).startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(10, 0))
                .slotType(ViewingSlot.MORNING).build();
        if (reschedule) {
            a.setProposedDate(LocalDate.now().plusDays(2));
            a.setProposedStartTime(LocalTime.of(14, 0));
            a.setProposedEndTime(LocalTime.of(15, 0));
            a.setProposedSlotType(ViewingSlot.AFTERNOON);
        }
        a.setActive(true);
        return a;
    }

    private static ViewingAppointmentService serviceFor(ViewingAppointment appointment) {
        ViewingAppointmentRepository appointments = mock(ViewingAppointmentRepository.class);
        when(appointments.findByIdWithListing(anyString())).thenReturn(Optional.of(appointment));
        when(appointments.save(any(ViewingAppointment.class))).thenAnswer(call -> call.getArgument(0));
        return new ViewingAppointmentService(appointments, mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageProperties.class));
    }
}
