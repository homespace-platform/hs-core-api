package com.hs.listing.service;

import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.notification.AppointmentNotificationWriter;
import com.hs.listing.repository.ViewingAppointmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppointmentReminderSchedulerTest {
    final ViewingAppointmentRepository appointments=mock(ViewingAppointmentRepository.class);
    final AppointmentNotificationWriter notices=mock(AppointmentNotificationWriter.class);
    final AppointmentReminderScheduler scheduler=new AppointmentReminderScheduler(appointments,notices,mock(org.springframework.transaction.PlatformTransactionManager.class));
    final LocalDateTime now=LocalDateTime.of(2026,11,17,9,0);
    ViewingAppointment appointment(AppointmentStatus status) {
        var a=ViewingAppointment.builder().id("booking").ownerId("owner").renterId("renter").status(status)
            .appointmentDate(now.toLocalDate()).startTime(now.toLocalTime().plusHours(1)).build();
        when(appointments.findByIdForUpdate("booking")).thenReturn(Optional.of(a));return a;
    }
    @Test void bothPartiesAreRemindedOncePerConfirmedScheduleAndOnlyBeforeStart() {
        var a=appointment(AppointmentStatus.CONFIRMED);scheduler.remindLocked("booking",now);scheduler.remindLocked("booking",now.plusMinutes(1));
        for(var side:AppointmentNotificationWriter.Side.values())verify(notices).enqueue(a,AppointmentNotificationWriter.Event.BOOKING_REMINDER,side);
        assertEquals(a.getAppointmentDate(),a.getReminderAppointmentDate());assertEquals(a.getStartTime(),a.getReminderStartTime());
        a.setStartTime(now.toLocalTime().plusHours(2));scheduler.remindLocked("booking",now);
        for(var side:AppointmentNotificationWriter.Side.values())verify(notices,times(2)).enqueue(a,AppointmentNotificationWriter.Event.BOOKING_REMINDER,side);
        assertEquals(AppointmentStatus.CONFIRMED,a.getStatus());
    }
    @ParameterizedTest @EnumSource(value=AppointmentStatus.class,names={"PENDING","REJECTED","CANCELLED","COMPLETED","EXPIRED"})
    void nonConfirmedOrCancelledBookingIsNeverReminded(AppointmentStatus status) {
        appointment(status);scheduler.remindLocked("booking",now);verifyNoInteractions(notices);
    }
    @Test void staleCandidatePastTooEarlyInactiveOrChangedScheduleIsCheckedAgainUnderLock() {
        var a=appointment(AppointmentStatus.CONFIRMED);a.setAppointmentDate(now.plusDays(2).toLocalDate());scheduler.remindLocked("booking",now);
        a.setAppointmentDate(now.toLocalDate());a.setStartTime(now.toLocalTime());scheduler.remindLocked("booking",now);
        a.setStartTime(now.plusHours(1).toLocalTime());a.setActive(false);scheduler.remindLocked("booking",now);
        verifyNoInteractions(notices);verify(appointments,never()).save(any());
    }
    @Test void schedulerScansOnlyOneBoundedPage() {
        when(appointments.findReminderIds(any(),any(),any(),any(),any())).thenReturn(java.util.List.of());scheduler.remindUpcoming();
        verify(appointments).findReminderIds(any(),any(),any(),any(),argThat(p->p.getPageSize()==100&&p.getPageNumber()==0));
    }
}
