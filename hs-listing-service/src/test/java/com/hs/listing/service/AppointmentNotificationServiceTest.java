package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.dto.request.*;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.*;
import com.hs.listing.notification.AppointmentNotificationWriter;
import com.hs.listing.repository.*;
import com.hs.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.*;
import java.util.*;

import static com.hs.listing.notification.AppointmentNotificationWriter.Event.*;
import static com.hs.listing.notification.AppointmentNotificationWriter.Side.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppointmentNotificationServiceTest {
    final ViewingAppointmentRepository appointments = mock(ViewingAppointmentRepository.class);
    final ListingRepository listings = mock(ListingRepository.class);
    final AppointmentNotificationWriter notifications = mock(AppointmentNotificationWriter.class);
    final ViewingAppointmentService service = new ViewingAppointmentService(appointments, listings,
            mock(ListingAddressRepository.class), mock(StorageProperties.class), notifications);
    Listing listing;
    ViewingAppointment apt;

    @BeforeEach
    void prepare() {
        listing = Listing.builder().id("listing").ownerId("owner").status(ListingStatus.PUBLISHED)
                .viewingDays(Set.of(DayOfWeek.values())).viewingSlots(Set.of(ListingEnums.ViewingSlot.MORNING)).build();
        apt = ViewingAppointment.builder().id("booking").ownerId("owner").renterId("renter")
                .listing(listing).status(AppointmentStatus.PENDING).appointmentDate(LocalDate.now().plusDays(2))
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0)).build();
        when(appointments.findByIdForUpdate("booking")).thenReturn(Optional.of(apt));
        when(appointments.findByIdWithListing("booking")).thenReturn(Optional.of(apt));
        when(appointments.save(any())).thenAnswer(call -> call.getArgument(0));
        when(listings.findByIdForAppointmentCreation("listing")).thenReturn(Optional.of(listing));
    }

    @Test
    void allManualTransitionsNotifyTheirCounterparty() {
        service.createAppointment("renter", null, new CreateAppointmentRequest("listing",
                LocalDate.now().plusDays(3), LocalTime.of(8, 0), LocalTime.of(9, 0), "Guest", "0901234567", 1, null));
        verify(notifications).enqueue(any(), eq(BOOKING_REQUESTED), eq(OWNER));
        clearInvocations(notifications);
        service.approveAppointment("owner", "booking", null);
        expect(BOOKING_CONFIRMED, RENTER);
        service.requestReschedule("renter", "booking", new RescheduleAppointmentRequest(
                LocalDate.now().plusDays(4), LocalTime.of(8, 0), LocalTime.of(9, 0), null));
        expect(BOOKING_RESCHEDULE_REQUESTED, OWNER);
        service.rejectReschedule("owner", "booking", new RejectAppointmentRequest("Unavailable"));
        expect(BOOKING_RESCHEDULE_REJECTED, RENTER);
        service.requestReschedule("renter", "booking", new RescheduleAppointmentRequest(
                LocalDate.now().plusDays(4), LocalTime.of(8, 0), LocalTime.of(9, 0), null));
        expect(BOOKING_RESCHEDULE_REQUESTED, OWNER);
        service.approveReschedule("owner", "booking");
        expect(BOOKING_RESCHEDULE_APPROVED, RENTER);
        service.cancelByOwner("owner", "booking", null);
        expect(BOOKING_CANCELLED, RENTER);

        apt.setStatus(AppointmentStatus.PENDING);
        service.rejectAppointment("owner", "booking", new RejectAppointmentRequest("Unavailable"));
        expect(BOOKING_REJECTED, RENTER);
        apt.setStatus(AppointmentStatus.PENDING);
        service.cancelByRenter("renter", "booking", null);
        expect(BOOKING_CANCELLED, OWNER);
        apt.setStatus(AppointmentStatus.CONFIRMED);
        service.completeAppointment("owner", "booking");
        expect(BOOKING_COMPLETED, RENTER);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED", "COMPLETED", "EXPIRED", "REJECTED"})
    void cannotResurrectOrRejectRescheduleOnTerminalAppointment(AppointmentStatus status) {
        apt.setStatus(status);
        apt.setRescheduleRequested(true);
        assertThrows(AppException.class, () -> service.approveReschedule("owner", "booking"));
        assertThrows(AppException.class, () -> service.rejectReschedule("owner", "booking", new RejectAppointmentRequest("No")));
        verifyNoInteractions(notifications);
    }

    @Test
    void terminalTransitionsDiscardProposalAndDoNotNotifyOnInvalidRepeat() {
        apt.setRescheduleRequested(true);
        apt.setProposedDate(LocalDate.now().plusDays(5));
        service.cancelByRenter("renter", "booking", null);
        assertFalse(apt.getRescheduleRequested());
        assertNull(apt.getProposedDate());
        expect(BOOKING_CANCELLED, OWNER);
        assertThrows(AppException.class, () -> service.cancelByRenter("renter", "booking", null));
        verifyNoInteractions(notifications);
    }

    @Test
    void automaticTransitionsNotifyBothSidesAndListingCancellationExcludesActor() {
        when(appointments.findActiveFutureAppointmentsByListing(anyString(), anyCollection(), any(), any())).thenReturn(List.of(apt));
        service.cancelActiveAppointmentsForListing("listing", ListingStatus.HIDDEN, "owner");
        expect(BOOKING_CANCELLED, RENTER);
        service.cancelActiveAppointmentsForListing("listing", ListingStatus.HIDDEN, "admin");
        verify(notifications).enqueue(apt, BOOKING_CANCELLED, RENTER);
        verify(notifications).enqueue(apt, BOOKING_CANCELLED, OWNER);
        verifyNoMoreInteractions(notifications);
        clearInvocations(notifications);
        when(appointments.findExpiredPendingAppointments(any(), any())).thenReturn(List.of(apt));
        service.autoExpirePendingAppointments();
        verify(notifications).enqueue(apt, BOOKING_EXPIRED, OWNER);
        verify(notifications).enqueue(apt, BOOKING_EXPIRED, RENTER);
        clearInvocations(notifications);
        when(appointments.findPastConfirmedAppointments(any(), any())).thenReturn(List.of(apt));
        service.autoCompleteConfirmedAppointments();
        verify(notifications).enqueue(apt, BOOKING_COMPLETED, OWNER);
        verify(notifications).enqueue(apt, BOOKING_COMPLETED, RENTER);
    }

    @Test
    void detailIsAvailableOnlyToTheTwoParticipants() {
        assertEquals("booking", service.getAppointment("owner", "booking").id());
        assertEquals("booking", service.getAppointment("renter", "booking").id());
        assertThrows(AppException.class, () -> service.getAppointment("outsider", "booking"));
        assertThrows(AppException.class, () -> service.getAppointment(null, "booking"));
        verifyNoInteractions(notifications);
    }

    private void expect(AppointmentNotificationWriter.Event event, AppointmentNotificationWriter.Side side) {
        verify(notifications).enqueue(apt, event, side);
        verifyNoMoreInteractions(notifications);
        clearInvocations(notifications);
    }
}
