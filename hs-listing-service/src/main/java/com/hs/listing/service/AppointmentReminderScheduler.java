package com.hs.listing.service;

import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.notification.AppointmentNotificationWriter;
import com.hs.listing.repository.ViewingAppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.Objects;

@Component @RequiredArgsConstructor @Slf4j
public class AppointmentReminderScheduler {
    private final ViewingAppointmentRepository appointments;
    private final AppointmentNotificationWriter notifications;
    private final PlatformTransactionManager transactions;

    @Scheduled(fixedDelay=60000)
    public void remindUpcoming() {
        var now=LocalDateTime.now(ViewingAppointmentService.VIETNAM_ZONE);
        var last=now.plusHours(24);
        for(String id:appointments.findReminderIds(now.toLocalDate(),now.toLocalTime(),last.toLocalDate(),last.toLocalTime(),PageRequest.of(0,100))) {
            try { new TransactionTemplate(transactions).executeWithoutResult(tx->remindLocked(id,now)); }
            catch(RuntimeException failure) { log.warn("Appointment reminder deferred ({})",failure.getClass().getSimpleName()); }
        }
    }
    void remindLocked(String id,LocalDateTime now) {
        appointments.findByIdForUpdate(id).filter(a->a.getStatus()==AppointmentStatus.CONFIRMED
                && Boolean.TRUE.equals(a.getActive()) && a.getAppointmentDate()!=null && a.getStartTime()!=null).ifPresent(a->{
            var start=a.getAppointmentDate().atTime(a.getStartTime());
            if(!start.isAfter(now) || start.isAfter(now.plusHours(24))
                    || Objects.equals(a.getReminderAppointmentDate(),a.getAppointmentDate()) && Objects.equals(a.getReminderStartTime(),a.getStartTime()))return;
            notifications.enqueue(a,AppointmentNotificationWriter.Event.BOOKING_REMINDER,AppointmentNotificationWriter.Side.OWNER);
            notifications.enqueue(a,AppointmentNotificationWriter.Event.BOOKING_REMINDER,AppointmentNotificationWriter.Side.RENTER);
            a.setReminderAppointmentDate(a.getAppointmentDate());a.setReminderStartTime(a.getStartTime());appointments.save(a);
        });
    }
}
