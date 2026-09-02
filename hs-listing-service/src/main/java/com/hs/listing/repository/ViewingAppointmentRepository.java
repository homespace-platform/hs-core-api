package com.hs.listing.repository;

import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ViewingAppointmentRepository
                extends JpaRepository<ViewingAppointment, String>, JpaSpecificationExecutor<ViewingAppointment> {

        Optional<ViewingAppointment> findByIdAndActiveTrue(String id);

        @Query("SELECT va FROM ViewingAppointment va JOIN FETCH va.listing l WHERE va.id = :id AND va.active = true")
        Optional<ViewingAppointment> findByIdWithListing(@Param("id") String id);

        @Query("SELECT va FROM ViewingAppointment va WHERE va.renterId = :renterId AND va.listing.id = :listingId AND va.active = true AND va.status IN :statuses")
        List<ViewingAppointment> findActiveByRenterIdAndListingId(
                        @Param("renterId") String renterId,
                        @Param("listingId") String listingId,
                        @Param("statuses") Collection<AppointmentStatus> statuses);

        @Query("SELECT va FROM ViewingAppointment va WHERE va.listing.id = :listingId AND va.appointmentDate = :date AND va.active = true AND va.status IN :statuses")
        List<ViewingAppointment> findByListingIdAndDateAndStatusIn(
                        @Param("listingId") String listingId,
                        @Param("date") LocalDate date,
                        @Param("statuses") Collection<AppointmentStatus> statuses);

        @Query("SELECT COUNT(va) > 0 FROM ViewingAppointment va WHERE va.listing.id = :listingId AND va.appointmentDate = :date AND va.startTime = :startTime AND va.status = com.hs.listing.model.constant.AppointmentStatus.CONFIRMED AND va.active = true")
        boolean isSlotConfirmed(
                        @Param("listingId") String listingId,
                        @Param("date") LocalDate date,
                        @Param("startTime") LocalTime startTime);

        @Query("SELECT va FROM ViewingAppointment va WHERE va.listing.id = :listingId AND va.appointmentDate = :date AND va.startTime = :startTime AND va.status = com.hs.listing.model.constant.AppointmentStatus.PENDING AND va.id != :confirmedId AND va.active = true")
        List<ViewingAppointment> findConflictingPendingAppointments(
                        @Param("listingId") String listingId,
                        @Param("date") LocalDate date,
                        @Param("startTime") LocalTime startTime,
                        @Param("confirmedId") String confirmedId);

        @Query("SELECT va FROM ViewingAppointment va WHERE va.listing.id = :listingId AND va.active = true AND va.status IN :statuses AND (va.appointmentDate > :today OR (va.appointmentDate = :today AND va.endTime > :now))")
        List<ViewingAppointment> findActiveFutureAppointmentsByListing(
                        @Param("listingId") String listingId,
                        @Param("statuses") Collection<AppointmentStatus> statuses,
                        @Param("today") LocalDate today,
                        @Param("now") LocalTime now);

        long countByOwnerIdAndStatusAndActiveTrue(String ownerId, AppointmentStatus status);

        long countByOwnerIdAndActiveTrue(String ownerId);

        @Query("SELECT va FROM ViewingAppointment va WHERE va.status = com.hs.listing.model.constant.AppointmentStatus.PENDING AND va.active = true AND (va.appointmentDate < :today OR (va.appointmentDate = :today AND va.endTime < :now))")
        List<ViewingAppointment> findExpiredPendingAppointments(
                        @Param("today") LocalDate today,
                        @Param("now") LocalTime now);

        @Query("SELECT va FROM ViewingAppointment va WHERE va.status = com.hs.listing.model.constant.AppointmentStatus.CONFIRMED AND va.active = true AND (va.appointmentDate < :today OR (va.appointmentDate = :today AND va.endTime < :now))")
        List<ViewingAppointment> findPastConfirmedAppointments(
                        @Param("today") LocalDate today,
                        @Param("now") LocalTime now);
}
