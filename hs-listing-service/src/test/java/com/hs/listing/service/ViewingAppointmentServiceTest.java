package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.ApproveAppointmentRequest;
import com.hs.listing.dto.request.CreateAppointmentRequest;
import com.hs.listing.dto.request.RejectAppointmentRequest;
import com.hs.listing.dto.request.RescheduleAppointmentRequest;
import com.hs.listing.dto.response.AvailabilitySlotResponse;
import com.hs.listing.dto.response.ListingAvailabilityResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ViewingAppointmentRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.AddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViewingAppointmentServiceTest {

    private ViewingAppointmentRepository appointmentRepository;
    private ListingRepository listingRepository;
    private AddressRepository addressRepository;
    private StorageProperties storageProperties;
    private ViewingAppointmentService appointmentService;

    private Listing testListing;
    private final String LISTING_ID = "listing-123";
    private final String OWNER_ID = "owner-999";

    @BeforeEach
    void setUp() {
        appointmentRepository = mock(ViewingAppointmentRepository.class);
        listingRepository = mock(ListingRepository.class);
        addressRepository = mock(AddressRepository.class);
        storageProperties = mock(StorageProperties.class);

        appointmentService = new ViewingAppointmentService(
                appointmentRepository,
                listingRepository,
                addressRepository,
                storageProperties
        );

        testListing = Listing.builder()
                .id(LISTING_ID)
                .ownerId(OWNER_ID)
                .status(ListingStatus.PUBLISHED)
                .viewingDays(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                .viewingSlots(Set.of(ViewingSlot.MORNING, ViewingSlot.AFTERNOON, ViewingSlot.EVENING))
                .build();
        testListing.setActive(true);

        when(listingRepository.findByIdAndActiveTrue(LISTING_ID)).thenReturn(Optional.of(testListing));
    }

    @Nested
    @DisplayName("1. Availability Tests (0/1/2/3 appointments & counting PENDING + CONFIRMED)")
    class AvailabilityTests {

        @Test
        @DisplayName("Slot with 0 bookings returns AVAILABLE and bookingCount = 0")
        void testAvailability_zeroBookings() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);
            when(appointmentRepository.findByListingIdAndDateAndStatusIn(eq(LISTING_ID), eq(futureDate), anyCollection()))
                    .thenReturn(List.of());

            ListingAvailabilityResponse res = appointmentService.getAvailability(LISTING_ID, futureDate, "renter-1");

            assertNotNull(res);
            assertTrue(res.isDayAvailable());
            assertFalse(res.slots().isEmpty());

            AvailabilitySlotResponse slot8to9 = res.slots().stream()
                    .filter(s -> s.startTime().equals(LocalTime.of(8, 0)))
                    .findFirst().orElseThrow();

            assertEquals("AVAILABLE", slot8to9.status());
            assertEquals(0, slot8to9.bookingCount());
        }

        @Test
        @DisplayName("Slot with 1, 2, 3 bookings correctly counts PENDING + CONFIRMED without locking")
        void testAvailability_multipleBookings_countsCorrectlyAndDoesNotLock() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);

            LocalTime slotTime = LocalTime.of(9, 0);
            LocalTime slotEndTime = LocalTime.of(10, 0);

            // 3 appointments in the same 09:00 - 10:00 slot
            ViewingAppointment apt1 = ViewingAppointment.builder()
                    .id("apt-1").renterId("renter-A").startTime(slotTime).endTime(slotEndTime)
                    .status(AppointmentStatus.CONFIRMED).visitorCount(3).build();

            ViewingAppointment apt2 = ViewingAppointment.builder()
                    .id("apt-2").renterId("renter-B").startTime(slotTime).endTime(slotEndTime)
                    .status(AppointmentStatus.PENDING).visitorCount(2).build();

            ViewingAppointment apt3 = ViewingAppointment.builder()
                    .id("apt-3").renterId("renter-C").startTime(slotTime).endTime(slotEndTime)
                    .status(AppointmentStatus.PENDING).visitorCount(1).build();

            when(appointmentRepository.findByListingIdAndDateAndStatusIn(eq(LISTING_ID), eq(futureDate), anyCollection()))
                    .thenReturn(List.of(apt1, apt2, apt3));

            // Third-party viewer (renter-X) queries availability
            ListingAvailabilityResponse res = appointmentService.getAvailability(LISTING_ID, futureDate, "renter-X");

            AvailabilitySlotResponse slot9to10 = res.slots().stream()
                    .filter(s -> s.startTime().equals(slotTime))
                    .findFirst().orElseThrow();

            // Total count is 3 appointments (NOT visitorCount sum 3+2+1=6)
            assertEquals(3, slot9to10.bookingCount());
            // Does NOT lock for other renters -> AVAILABLE
            assertEquals("AVAILABLE", slot9to10.status());
        }

        @Test
        @DisplayName("Slot correctly reflects CONFIRMED_YOU and PENDING_YOU for current user")
        void testAvailability_userSpecificStatus() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);
            LocalTime slot1 = LocalTime.of(8, 0);
            LocalTime slot2 = LocalTime.of(10, 0);

            ViewingAppointment aptPending = ViewingAppointment.builder()
                    .id("apt-p").renterId("my-user-id").startTime(slot1).endTime(LocalTime.of(9, 0))
                    .status(AppointmentStatus.PENDING).visitorCount(1).build();

            ViewingAppointment aptConfirmed = ViewingAppointment.builder()
                    .id("apt-c").renterId("my-user-id").startTime(slot2).endTime(LocalTime.of(11, 0))
                    .status(AppointmentStatus.CONFIRMED).visitorCount(1).build();

            // Also another user's appointment in slot1
            ViewingAppointment otherUserApt = ViewingAppointment.builder()
                    .id("apt-other").renterId("other-user").startTime(slot1).endTime(LocalTime.of(9, 0))
                    .status(AppointmentStatus.CONFIRMED).visitorCount(1).build();

            when(appointmentRepository.findByListingIdAndDateAndStatusIn(eq(LISTING_ID), eq(futureDate), anyCollection()))
                    .thenReturn(List.of(aptPending, otherUserApt, aptConfirmed));

            ListingAvailabilityResponse res = appointmentService.getAvailability(LISTING_ID, futureDate, "my-user-id");

            AvailabilitySlotResponse slot8to9 = res.slots().stream()
                    .filter(s -> s.startTime().equals(slot1))
                    .findFirst().orElseThrow();

            // Has 2 appointments, user is PENDING
            assertEquals(2, slot8to9.bookingCount());
            assertEquals("PENDING_YOU", slot8to9.status());

            AvailabilitySlotResponse slot10to11 = res.slots().stream()
                    .filter(s -> s.startTime().equals(slot2))
                    .findFirst().orElseThrow();

            // Has 1 appointment, user is CONFIRMED
            assertEquals(1, slot10to11.bookingCount());
            assertEquals("CONFIRMED_YOU", slot10to11.status());
        }
    }

    @Nested
    @DisplayName("2. Create Appointment Tests")
    class CreateAppointmentTests {

        @Test
        @DisplayName("Create appointment succeeds even if slot already has CONFIRMED bookings from other users")
        void testCreateAppointment_succeedsWhenOtherBookingsExist() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);
            CreateAppointmentRequest req = new CreateAppointmentRequest(
                    LISTING_ID,
                    futureDate,
                    LocalTime.of(9, 0),
                    LocalTime.of(10, 0),
                    "Nguyen Van A",
                    "0901234567",
                    2,
                    "Muon xem phong som"
            );

            when(appointmentRepository.findActiveByRenterIdAndListingId(eq("renter-new"), eq(LISTING_ID), anyCollection()))
                    .thenReturn(List.of());
            when(appointmentRepository.save(any(ViewingAppointment.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var response = appointmentService.createAppointment("renter-new", "new@example.com", req);

            assertNotNull(response);
            assertEquals(AppointmentStatus.PENDING, response.status());
            assertEquals(LISTING_ID, response.listingId());
            assertEquals("renter-new", response.renterId());
        }

        @Test
        @DisplayName("Reject if renter already has an active appointment for the same listing")
        void testCreateAppointment_duplicateActiveRenterFails() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);
            CreateAppointmentRequest req = new CreateAppointmentRequest(
                    LISTING_ID,
                    futureDate,
                    LocalTime.of(9, 0),
                    LocalTime.of(10, 0),
                    "Nguyen Van A",
                    "0901234567",
                    1,
                    null
            );

            ViewingAppointment existing = ViewingAppointment.builder()
                    .id("existing-apt").renterId("renter-1").listing(testListing)
                    .status(AppointmentStatus.PENDING).build();

            when(appointmentRepository.findActiveByRenterIdAndListingId(eq("renter-1"), eq(LISTING_ID), anyCollection()))
                    .thenReturn(List.of(existing));

            AppException ex = assertThrows(AppException.class, () ->
                    appointmentService.createAppointment("renter-1", "test@test.com", req));
            assertEquals(ListingErrorCode.APPOINTMENT_ALREADY_EXISTS.getCode(), ex.getCode());
        }

        @Test
        @DisplayName("Reject if owner tries to book their own listing")
        void testCreateAppointment_ownerCannotBookOwnListing() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);
            CreateAppointmentRequest req = new CreateAppointmentRequest(
                    LISTING_ID, futureDate, LocalTime.of(9, 0), LocalTime.of(10, 0),
                    "Owner Name", "0901234567", 1, null);

            AppException ex = assertThrows(AppException.class, () ->
                    appointmentService.createAppointment(OWNER_ID, "owner@test.com", req));
            assertEquals(ListingErrorCode.CANNOT_BOOK_OWN_LISTING.getCode(), ex.getCode());
        }
    }

    @Nested
    @DisplayName("3. Approve Appointment Tests (No auto-rejection of other PENDING)")
    class ApproveAppointmentTests {

        @Test
        @DisplayName("Approving an appointment does NOT reject other PENDING appointments in the same slot")
        void testApprove_doesNotRejectOtherPendings() {
            LocalDate futureDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(2);
            LocalTime start = LocalTime.of(9, 0);
            LocalTime end = LocalTime.of(10, 0);

            ViewingAppointment aptToApprove = ViewingAppointment.builder()
                    .id("apt-1").ownerId(OWNER_ID).renterId("renter-1").listing(testListing)
                    .appointmentDate(futureDate).startTime(start).endTime(end)
                    .status(AppointmentStatus.PENDING).build();

            when(appointmentRepository.findByIdWithListing("apt-1")).thenReturn(Optional.of(aptToApprove));
            when(appointmentRepository.save(any(ViewingAppointment.class))).thenAnswer(inv -> inv.getArgument(0));

            ApproveAppointmentRequest approveReq = new ApproveAppointmentRequest("Nho den dung gio nhe");
            var res = appointmentService.approveAppointment(OWNER_ID, "apt-1", approveReq);

            assertNotNull(res);
            assertEquals(AppointmentStatus.CONFIRMED, res.status());
            assertEquals("Nho den dung gio nhe", res.ownerNote());

            // Verify findConflictingPendingAppointments is NOT called / other appointments are not touched
            verify(appointmentRepository, never()).findConflictingPendingAppointments(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("4. Reschedule Appointment Tests")
    class RescheduleTests {

        @Test
        @DisplayName("Renter requests reschedule into a slot already containing bookings; owner approves without rejecting other bookings")
        void testReschedule_succeedsWithoutConflictRejection() {
            LocalDate newDate = LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(3);
            ViewingAppointment existingApt = ViewingAppointment.builder()
                    .id("apt-reschedule").ownerId(OWNER_ID).renterId("renter-1").listing(testListing)
                    .appointmentDate(LocalDate.now(ViewingAppointmentService.VIETNAM_ZONE).plusDays(1))
                    .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(9, 0))
                    .status(AppointmentStatus.CONFIRMED)
                    .rescheduleRequested(false)
                    .build();

            when(appointmentRepository.findByIdWithListing("apt-reschedule")).thenReturn(Optional.of(existingApt));
            when(appointmentRepository.save(any(ViewingAppointment.class))).thenAnswer(inv -> inv.getArgument(0));

            RescheduleAppointmentRequest req = new RescheduleAppointmentRequest(
                    newDate, LocalTime.of(14, 0), LocalTime.of(15, 0), "Ban viec dot xuat");

            var resReq = appointmentService.requestReschedule("renter-1", "apt-reschedule", req);
            assertTrue(resReq.rescheduleRequested());
            assertEquals(newDate, resReq.proposedDate());

            // Now owner approves reschedule
            var resApprove = appointmentService.approveReschedule(OWNER_ID, "apt-reschedule");
            assertEquals(AppointmentStatus.CONFIRMED, resApprove.status());
            assertEquals(newDate, resApprove.appointmentDate());
            assertEquals(LocalTime.of(14, 0), resApprove.startTime());
            assertFalse(resApprove.rescheduleRequested());

            // No conflicting appointments auto-rejected
            verify(appointmentRepository, never()).findConflictingPendingAppointments(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("5. Scheduler Lifecycle Tests (Auto-expire and Auto-complete)")
    class SchedulerTests {

        @Test
        @DisplayName("autoExpirePendingAppointments expires past PENDING appointments")
        void testAutoExpirePending() {
            ViewingAppointment apt1 = ViewingAppointment.builder()
                    .id("p-1").status(AppointmentStatus.PENDING).build();
            ViewingAppointment apt2 = ViewingAppointment.builder()
                    .id("p-2").status(AppointmentStatus.PENDING).build();

            when(appointmentRepository.findExpiredPendingAppointments(any(LocalDate.class), any(LocalTime.class)))
                    .thenReturn(List.of(apt1, apt2));

            int count = appointmentService.autoExpirePendingAppointments();

            assertEquals(2, count);
            assertEquals(AppointmentStatus.EXPIRED, apt1.getStatus());
            assertEquals(AppointmentStatus.EXPIRED, apt2.getStatus());
            verify(appointmentRepository, times(2)).save(any(ViewingAppointment.class));
        }

        @Test
        @DisplayName("autoCompleteConfirmedAppointments marks past CONFIRMED as COMPLETED with timestamp")
        void testAutoCompleteConfirmed() {
            ViewingAppointment c1 = ViewingAppointment.builder()
                    .id("c-1").status(AppointmentStatus.CONFIRMED).build();

            when(appointmentRepository.findPastConfirmedAppointments(any(LocalDate.class), any(LocalTime.class)))
                    .thenReturn(List.of(c1));

            int count = appointmentService.autoCompleteConfirmedAppointments();

            assertEquals(1, count);
            assertEquals(AppointmentStatus.COMPLETED, c1.getStatus());
            assertNotNull(c1.getCompletedAt());
            verify(appointmentRepository, times(1)).save(c1);
        }
    }
}
