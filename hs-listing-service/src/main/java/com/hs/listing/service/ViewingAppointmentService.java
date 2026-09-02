package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.*;
import com.hs.listing.dto.response.AppointmentResponse;
import com.hs.listing.dto.response.AvailabilitySlotResponse;
import com.hs.listing.dto.response.ListingAvailabilityResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ViewingAppointment;
import com.hs.listing.model.constant.AppointmentCancelledBy;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.model.ListingMedia;
import com.hs.listing.model.constant.ListingEnums.ViewingSlot;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ViewingAppointmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ViewingAppointmentService {

    private final ViewingAppointmentRepository appointmentRepository;
    private final ListingRepository listingRepository;
    private final AddressRepository addressRepository;
    private final StorageProperties storageProperties;

    // Định nghĩa các khung giờ 1 tiếng cố định cho từng buổi
    private static final List<TimeSlotDef> MORNING_SLOTS = List.of(
            new TimeSlotDef(LocalTime.of(8, 0), LocalTime.of(9, 0), ViewingSlot.MORNING),
            new TimeSlotDef(LocalTime.of(9, 0), LocalTime.of(10, 0), ViewingSlot.MORNING),
            new TimeSlotDef(LocalTime.of(10, 0), LocalTime.of(11, 0), ViewingSlot.MORNING),
            new TimeSlotDef(LocalTime.of(11, 0), LocalTime.of(12, 0), ViewingSlot.MORNING)
    );

    private static final List<TimeSlotDef> AFTERNOON_SLOTS = List.of(
            new TimeSlotDef(LocalTime.of(13, 0), LocalTime.of(14, 0), ViewingSlot.AFTERNOON),
            new TimeSlotDef(LocalTime.of(14, 0), LocalTime.of(15, 0), ViewingSlot.AFTERNOON),
            new TimeSlotDef(LocalTime.of(15, 0), LocalTime.of(16, 0), ViewingSlot.AFTERNOON),
            new TimeSlotDef(LocalTime.of(16, 0), LocalTime.of(17, 0), ViewingSlot.AFTERNOON)
    );

    private static final List<TimeSlotDef> EVENING_SLOTS = List.of(
            new TimeSlotDef(LocalTime.of(17, 0), LocalTime.of(18, 0), ViewingSlot.EVENING),
            new TimeSlotDef(LocalTime.of(18, 0), LocalTime.of(19, 0), ViewingSlot.EVENING),
            new TimeSlotDef(LocalTime.of(19, 0), LocalTime.of(20, 0), ViewingSlot.EVENING),
            new TimeSlotDef(LocalTime.of(20, 0), LocalTime.of(21, 0), ViewingSlot.EVENING)
    );

    public record TimeSlotDef(LocalTime start, LocalTime end, ViewingSlot slot) {}

    // =========================================================================
    // 1. LẤY DANH SÁCH KHUNG GIỜ KHẢ DỤNG (AVAILABILITY)
    // =========================================================================
    @Transactional(readOnly = true)
    public ListingAvailabilityResponse getAvailability(String listingId, LocalDate date, String currentUserId) {
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));

        LocalDate queryDate = date != null ? date : LocalDate.now();
        DayOfWeek dayOfWeek = queryDate.getDayOfWeek();

        List<DayOfWeek> allowedDays = listing.getViewingDays() != null
                ? listing.getViewingDays().stream().sorted().toList()
                : List.of();

        List<ViewingSlot> allowedSlots = listing.getViewingSlots() != null
                ? listing.getViewingSlots().stream().sorted().toList()
                : List.of();

        boolean isDayAvailable = allowedDays.contains(dayOfWeek)
                && listing.getStatus() == ListingStatus.PUBLISHED
                && !queryDate.isBefore(LocalDate.now());

        // Kiểm tra xem user hiện tại đã có lịch hẹn nào đang active (PENDING/CONFIRMED) cho bài này chưa
        boolean hasExistingActiveBooking = false;
        String existingBookingId = null;
        if (currentUserId != null && !currentUserId.isBlank()) {
            List<ViewingAppointment> existing = appointmentRepository.findActiveByRenterIdAndListingId(
                    currentUserId, listingId, Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));
            if (!existing.isEmpty()) {
                hasExistingActiveBooking = true;
                existingBookingId = existing.get(0).getId();
            }
        }

        List<AvailabilitySlotResponse> slots = new ArrayList<>();
        if (isDayAvailable) {
            // Lấy tất cả lịch hẹn của tin đăng trong ngày queryDate
            List<ViewingAppointment> appointmentsOnDate = appointmentRepository.findByListingIdAndDateAndStatusIn(
                    listingId, queryDate, Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));

            Map<LocalTime, ViewingAppointment> confirmedMap = new HashMap<>();
            Map<LocalTime, List<ViewingAppointment>> pendingMap = new HashMap<>();

            for (ViewingAppointment apt : appointmentsOnDate) {
                if (apt.getStatus() == AppointmentStatus.CONFIRMED) {
                    confirmedMap.put(apt.getStartTime(), apt);
                } else if (apt.getStatus() == AppointmentStatus.PENDING) {
                    pendingMap.computeIfAbsent(apt.getStartTime(), k -> new ArrayList<>()).add(apt);
                }
            }

            LocalDate today = LocalDate.now();
            LocalTime now = LocalTime.now();

            List<TimeSlotDef> candidateSlots = new ArrayList<>();
            if (allowedSlots.contains(ViewingSlot.MORNING)) candidateSlots.addAll(MORNING_SLOTS);
            if (allowedSlots.contains(ViewingSlot.AFTERNOON)) candidateSlots.addAll(AFTERNOON_SLOTS);
            if (allowedSlots.contains(ViewingSlot.EVENING)) candidateSlots.addAll(EVENING_SLOTS);

            for (TimeSlotDef def : candidateSlots) {
                String slotStatus = "AVAILABLE";

                // Nếu là ngày hôm nay và khung giờ quá sát hiện tại (dưới 1 tiếng) -> UNAVAILABLE
                if (queryDate.equals(today) && def.start().isBefore(now.plusHours(1))) {
                    slotStatus = "UNAVAILABLE";
                } else if (confirmedMap.containsKey(def.start())) {
                    ViewingAppointment confirmedApt = confirmedMap.get(def.start());
                    if (currentUserId != null && currentUserId.equals(confirmedApt.getRenterId())) {
                        slotStatus = "CONFIRMED_YOU";
                    } else {
                        slotStatus = "LOCKED"; // Đã có khách khác được duyệt -> Khóa khung giờ
                    }
                } else if (pendingMap.containsKey(def.start())) {
                    List<ViewingAppointment> pendings = pendingMap.get(def.start());
                    boolean youPending = currentUserId != null && pendings.stream()
                            .anyMatch(p -> currentUserId.equals(p.getRenterId()));
                    if (youPending) {
                        slotStatus = "PENDING_YOU";
                    } else {
                        slotStatus = "AVAILABLE"; // Khách khác pending nhưng chưa duyệt nên vẫn cho gửi yêu cầu
                    }
                }

                slots.add(AvailabilitySlotResponse.builder()
                        .startTime(def.start())
                        .endTime(def.end())
                        .slotType(def.slot())
                        .status(slotStatus)
                        .build());
            }
        }

        return ListingAvailabilityResponse.builder()
                .listingId(listingId)
                .date(queryDate)
                .dayOfWeek(dayOfWeek)
                .isDayAvailable(isDayAvailable)
                .allowedViewingDays(allowedDays)
                .allowedViewingSlots(allowedSlots)
                .slots(slots)
                .hasExistingActiveBooking(hasExistingActiveBooking)
                .existingBookingId(existingBookingId)
                .build();
    }

    // =========================================================================
    // 2. TẠO LỊCH HẸN XEM NHÀ MỚI (RENTER BOOKING)
    // =========================================================================
    @Transactional
    public AppointmentResponse createAppointment(String renterId, String renterEmail, CreateAppointmentRequest req) {
        if (renterId == null || renterId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Listing listing = listingRepository.findByIdAndActiveTrue(req.listingId())
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));

        // 1. Chỉ được đặt lịch khi bài đăng đang ở trạng thái PUBLISHED
        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.LISTING_NOT_AVAILABLE_FOR_VIEWING);
        }

        // 2. Chặn chính chủ tự đặt lịch xem tin của mình
        if (renterId.equals(listing.getOwnerId())) {
            throw new AppException(ListingErrorCode.CANNOT_BOOK_OWN_LISTING);
        }

        // 3. Quy tắc: 1 khách chỉ được đặt 1 khung giờ trong 1 bài đăng (PENDING hoặc CONFIRMED)
        List<ViewingAppointment> existingActive = appointmentRepository.findActiveByRenterIdAndListingId(
                renterId, req.listingId(), Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));
        if (!existingActive.isEmpty()) {
            throw new AppException(ListingErrorCode.APPOINTMENT_ALREADY_EXISTS);
        }

        // 4. Kiểm tra ngày giờ hợp lệ
        LocalDate today = LocalDate.now();
        if (req.appointmentDate().isBefore(today)) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_TIME);
        }
        // Đặt trước tối thiểu 2 tiếng nếu chọn lịch trong ngày hôm nay
        if (req.appointmentDate().equals(today) && req.startTime().isBefore(LocalTime.now().plusHours(2))) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_TIME);
        }

        // 5. Kiểm tra ngày trong tuần có thuộc lịch tiếp khách của chủ nhà không
        if (listing.getViewingDays() == null || !listing.getViewingDays().contains(req.appointmentDate().getDayOfWeek())) {
            throw new AppException(ListingErrorCode.DAY_NOT_AVAILABLE);
        }

        // 6. Kiểm tra khung giờ có thuộc buổi tiếp khách của chủ nhà không
        ViewingSlot slotType = resolveSlotType(req.startTime(), req.endTime());
        if (slotType == null || listing.getViewingSlots() == null || !listing.getViewingSlots().contains(slotType)) {
            throw new AppException(ListingErrorCode.SLOT_NOT_AVAILABLE);
        }

        // 7. Kiểm tra khung giờ này đã bị ai đó khóa (CONFIRMED) chưa
        if (appointmentRepository.isSlotConfirmed(req.listingId(), req.appointmentDate(), req.startTime())) {
            throw new AppException(ListingErrorCode.SLOT_ALREADY_BOOKED);
        }

        ViewingAppointment appointment = ViewingAppointment.builder()
                .id(UUID.randomUUID().toString())
                .listing(listing)
                .ownerId(listing.getOwnerId())
                .renterId(renterId)
                .renterName(req.renterName().trim())
                .renterPhone(req.renterPhone().trim())
                .renterEmail(renterEmail)
                .visitorCount(req.visitorCount() != null ? req.visitorCount() : 1)
                .renterNote(req.renterNote() != null ? req.renterNote().trim() : null)
                .appointmentDate(req.appointmentDate())
                .startTime(req.startTime())
                .endTime(req.endTime())
                .slotType(slotType)
                .status(AppointmentStatus.PENDING)
                .rescheduleRequested(false)
                .build();
        appointment.setActive(true);

        ViewingAppointment saved = appointmentRepository.save(appointment);
        log.info("Renter {} successfully requested viewing appointment {} for listing {} on {} at {}-{}",
                renterId, saved.getId(), req.listingId(), req.appointmentDate(), req.startTime(), req.endTime());

        return toResponse(saved);
    }

    // =========================================================================
    // 3. CHỦ NHÀ CHẤP NHẬN LỊCH HẸN (APPROVE) -> KHÓA KHUNG GIỜ ĐÓ LẠI
    // =========================================================================
    @Transactional
    public AppointmentResponse approveAppointment(String ownerId, String appointmentId, ApproveAppointmentRequest req) {
        ViewingAppointment apt = getAppointmentForOwner(ownerId, appointmentId);

        if (apt.getStatus() != AppointmentStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        if (apt.getListing().getStatus() != ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.LISTING_NOT_AVAILABLE_FOR_VIEWING);
        }

        // Kiểm tra xem trong thời gian chờ duyệt, có slot nào khác cùng giờ đã bị confirmed chưa
        if (appointmentRepository.isSlotConfirmed(apt.getListing().getId(), apt.getAppointmentDate(), apt.getStartTime())) {
            throw new AppException(ListingErrorCode.SLOT_ALREADY_BOOKED);
        }

        apt.setStatus(AppointmentStatus.CONFIRMED);
        if (req != null && req.ownerNote() != null && !req.ownerNote().isBlank()) {
            apt.setOwnerNote(req.ownerNote().trim());
        }
        ViewingAppointment confirmedApt = appointmentRepository.save(apt);
        log.info("Owner {} APPROVED viewing appointment {} for listing {} on {} at {}-{}",
                ownerId, appointmentId, apt.getListing().getId(), apt.getAppointmentDate(), apt.getStartTime(), apt.getEndTime());

        // XỬ LÝ XUNG ĐỘT (Race Condition):
        // Tự động từ chối các yêu cầu PENDING khác của cùng bài đăng, cùng ngày và khung giờ này
        List<ViewingAppointment> conflicts = appointmentRepository.findConflictingPendingAppointments(
                apt.getListing().getId(), apt.getAppointmentDate(), apt.getStartTime(), apt.getId());
        for (ViewingAppointment conflict : conflicts) {
            conflict.setStatus(AppointmentStatus.REJECTED);
            conflict.setRejectReason("Khung giờ này đã được xác nhận cho một khách hàng khác. Vui lòng chọn khung giờ khác.");
            appointmentRepository.save(conflict);
            log.info("Auto-rejected conflicting pending appointment {} for listing {} on {} slot {}",
                    conflict.getId(), apt.getListing().getId(), apt.getAppointmentDate(), apt.getStartTime());
        }

        return toResponse(confirmedApt);
    }

    // =========================================================================
    // 4. CHỦ NHÀ TỪ CHỐI LỊCH HẸN (REJECT)
    // =========================================================================
    @Transactional
    public AppointmentResponse rejectAppointment(String ownerId, String appointmentId, RejectAppointmentRequest req) {
        ViewingAppointment apt = getAppointmentForOwner(ownerId, appointmentId);

        if (apt.getStatus() != AppointmentStatus.PENDING) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        apt.setStatus(AppointmentStatus.REJECTED);
        apt.setRejectReason(req.rejectReason().trim());
        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Owner {} REJECTED viewing appointment {} with reason: {}", ownerId, appointmentId, req.rejectReason());

        return toResponse(saved);
    }

    // =========================================================================
    // 5. KHÁCH XIN ĐỔI LỊCH HẸN (RESCHEDULE REQUEST)
    // =========================================================================
    @Transactional
    public AppointmentResponse requestReschedule(String renterId, String appointmentId, RescheduleAppointmentRequest req) {
        ViewingAppointment apt = appointmentRepository.findByIdWithListing(appointmentId)
                .orElseThrow(() -> new AppException(ListingErrorCode.APPOINTMENT_NOT_FOUND));

        if (!renterId.equals(apt.getRenterId())) {
            throw new AppException(ListingErrorCode.APPOINTMENT_FORBIDDEN);
        }

        if (apt.getStatus() != AppointmentStatus.PENDING && apt.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        if (Boolean.TRUE.equals(apt.getRescheduleRequested())) {
            throw new AppException(ListingErrorCode.RESCHEDULE_ALREADY_REQUESTED);
        }

        Listing listing = apt.getListing();
        if (listing.getStatus() != ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.LISTING_NOT_AVAILABLE_FOR_VIEWING);
        }

        // Validate ngày giờ mới
        LocalDate today = LocalDate.now();
        if (req.proposedDate().isBefore(today)) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_TIME);
        }
        if (req.proposedDate().equals(today) && req.proposedStartTime().isBefore(LocalTime.now().plusHours(2))) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_TIME);
        }
        if (listing.getViewingDays() == null || !listing.getViewingDays().contains(req.proposedDate().getDayOfWeek())) {
            throw new AppException(ListingErrorCode.DAY_NOT_AVAILABLE);
        }
        ViewingSlot proposedSlotType = resolveSlotType(req.proposedStartTime(), req.proposedEndTime());
        if (proposedSlotType == null || listing.getViewingSlots() == null || !listing.getViewingSlots().contains(proposedSlotType)) {
            throw new AppException(ListingErrorCode.SLOT_NOT_AVAILABLE);
        }
        if (appointmentRepository.isSlotConfirmed(listing.getId(), req.proposedDate(), req.proposedStartTime())) {
            throw new AppException(ListingErrorCode.SLOT_ALREADY_BOOKED);
        }

        apt.setRescheduleRequested(true);
        apt.setProposedDate(req.proposedDate());
        apt.setProposedStartTime(req.proposedStartTime());
        apt.setProposedEndTime(req.proposedEndTime());
        apt.setProposedSlotType(proposedSlotType);
        apt.setRescheduleReason(req.rescheduleReason() != null ? req.rescheduleReason().trim() : null);

        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Renter {} requested reschedule for appointment {} to {} {}-{}",
                renterId, appointmentId, req.proposedDate(), req.proposedStartTime(), req.proposedEndTime());

        return toResponse(saved);
    }

    // =========================================================================
    // 6. CHỦ NHÀ DUYỆT ĐỔI LỊCH (APPROVE RESCHEDULE)
    // =========================================================================
    @Transactional
    public AppointmentResponse approveReschedule(String ownerId, String appointmentId) {
        ViewingAppointment apt = getAppointmentForOwner(ownerId, appointmentId);

        if (!Boolean.TRUE.equals(apt.getRescheduleRequested())) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        // Kiểm tra khung giờ mới xem có bị ai confirmed chưa
        if (appointmentRepository.isSlotConfirmed(apt.getListing().getId(), apt.getProposedDate(), apt.getProposedStartTime())) {
            throw new AppException(ListingErrorCode.SLOT_ALREADY_BOOKED);
        }

        LocalDate newDate = apt.getProposedDate();
        LocalTime newStart = apt.getProposedStartTime();
        LocalTime newEnd = apt.getProposedEndTime();
        ViewingSlot newSlotType = apt.getProposedSlotType();

        // Áp dụng lịch mới và khóa khung giờ mới, mở lại khung giờ cũ
        apt.setAppointmentDate(newDate);
        apt.setStartTime(newStart);
        apt.setEndTime(newEnd);
        apt.setSlotType(newSlotType);
        apt.setStatus(AppointmentStatus.CONFIRMED);
        apt.setRescheduleRequested(false);
        apt.setProposedDate(null);
        apt.setProposedStartTime(null);
        apt.setProposedEndTime(null);
        apt.setProposedSlotType(null);
        apt.setRescheduleReason(null);

        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Owner {} APPROVED reschedule for appointment {}. New schedule: {} {}-{}",
                ownerId, appointmentId, newDate, newStart, newEnd);

        // Tự động từ chối các yêu cầu PENDING khác trùng khung giờ mới
        List<ViewingAppointment> conflicts = appointmentRepository.findConflictingPendingAppointments(
                apt.getListing().getId(), newDate, newStart, apt.getId());
        for (ViewingAppointment conflict : conflicts) {
            conflict.setStatus(AppointmentStatus.REJECTED);
            conflict.setRejectReason("Khung giờ này đã được xác nhận cho một khách hàng khác. Vui lòng chọn khung giờ khác.");
            appointmentRepository.save(conflict);
        }

        return toResponse(saved);
    }

    // =========================================================================
    // 7. CHỦ NHÀ TỪ CHỐI ĐỔI LỊCH (REJECT RESCHEDULE)
    // =========================================================================
    @Transactional
    public AppointmentResponse rejectReschedule(String ownerId, String appointmentId, RejectAppointmentRequest req) {
        ViewingAppointment apt = getAppointmentForOwner(ownerId, appointmentId);

        if (!Boolean.TRUE.equals(apt.getRescheduleRequested())) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        apt.setRescheduleRequested(false);
        apt.setOwnerNote("Yêu cầu đổi lịch bị từ chối: " + req.rejectReason().trim());
        apt.setProposedDate(null);
        apt.setProposedStartTime(null);
        apt.setProposedEndTime(null);
        apt.setProposedSlotType(null);
        apt.setRescheduleReason(null);

        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Owner {} REJECTED reschedule for appointment {}", ownerId, appointmentId);

        return toResponse(saved);
    }

    // =========================================================================
    // 8. KHÁCH TỰ HỦY LỊCH HẸN (CANCEL BY RENTER)
    // =========================================================================
    @Transactional
    public AppointmentResponse cancelByRenter(String renterId, String appointmentId, CancelAppointmentRequest req) {
        ViewingAppointment apt = appointmentRepository.findByIdWithListing(appointmentId)
                .orElseThrow(() -> new AppException(ListingErrorCode.APPOINTMENT_NOT_FOUND));

        if (!renterId.equals(apt.getRenterId())) {
            throw new AppException(ListingErrorCode.APPOINTMENT_FORBIDDEN);
        }

        if (apt.getStatus() != AppointmentStatus.PENDING && apt.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        apt.setStatus(AppointmentStatus.CANCELLED);
        apt.setCancelledBy(AppointmentCancelledBy.RENTER);
        apt.setCancelReason(req != null && req.cancelReason() != null ? req.cancelReason().trim() : "Khách hàng tự hủy lịch hẹn");
        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Renter {} CANCELLED appointment {}", renterId, appointmentId);

        return toResponse(saved);
    }

    // =========================================================================
    // 9. CHỦ NHÀ HỦY LỊCH HẸN (CANCEL BY OWNER)
    // =========================================================================
    @Transactional
    public AppointmentResponse cancelByOwner(String ownerId, String appointmentId, CancelAppointmentRequest req) {
        ViewingAppointment apt = getAppointmentForOwner(ownerId, appointmentId);

        if (apt.getStatus() != AppointmentStatus.PENDING && apt.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        apt.setStatus(AppointmentStatus.CANCELLED);
        apt.setCancelledBy(AppointmentCancelledBy.OWNER);
        apt.setCancelReason(req != null && req.cancelReason() != null ? req.cancelReason().trim() : "Chủ nhà hủy lịch hẹn");
        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Owner {} CANCELLED appointment {}", ownerId, appointmentId);

        return toResponse(saved);
    }

    // =========================================================================
    // 10. CHỦ NHÀ XÁC NHẬN ĐÃ HOÀN THÀNH BUỔI XEM NHÀ (COMPLETE)
    // =========================================================================
    @Transactional
    public AppointmentResponse completeAppointment(String ownerId, String appointmentId) {
        ViewingAppointment apt = getAppointmentForOwner(ownerId, appointmentId);

        if (apt.getStatus() != AppointmentStatus.CONFIRMED) {
            throw new AppException(ListingErrorCode.INVALID_APPOINTMENT_STATUS);
        }

        apt.setStatus(AppointmentStatus.COMPLETED);
        apt.setCompletedAt(Instant.now());
        ViewingAppointment saved = appointmentRepository.save(apt);
        log.info("Owner {} marked appointment {} as COMPLETED", ownerId, appointmentId);

        return toResponse(saved);
    }

    // =========================================================================
    // 11. TỰ ĐỘNG HỦY LỊCH HẸN KHI BÀI ĐĂNG THAY ĐỔI TRẠNG THÁI (KHÔNG CÒN PUBLISHED)
    // =========================================================================
    @Transactional
    public void cancelActiveAppointmentsForListing(String listingId, ListingStatus newStatus) {
        if (listingId == null || listingId.isBlank()) return;

        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        List<ViewingAppointment> activeAppointments = appointmentRepository.findActiveFutureAppointmentsByListing(
                listingId, Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED), today, now);

        if (activeAppointments.isEmpty()) return;

        for (ViewingAppointment apt : activeAppointments) {
            apt.setStatus(AppointmentStatus.CANCELLED);
            apt.setCancelledBy(AppointmentCancelledBy.SYSTEM);
            apt.setCancelReason("Tin đăng không còn hiển thị (chuyển sang trạng thái " + newStatus + ")");
            appointmentRepository.save(apt);

            // TODO: [EMAIL NOTIFICATION] Gửi email thông báo hủy lịch xem nhà tới khách hàng khi email service hoàn thiện.
            // Template params: renterName, renterEmail, listingTitle, listingAddress, appointmentDate, timeSlot, cancelReason
            log.warn("[EMAIL_NOTIFICATION_TODO] Listing [{}] changed status to [{}]. Auto-cancelled appointment [{}] of renter [{}] (Email: {}). Scheduled for: {} {}-{}",
                    listingId, newStatus, apt.getId(), apt.getRenterName(), apt.getRenterEmail(),
                    apt.getAppointmentDate(), apt.getStartTime(), apt.getEndTime());
        }

        log.info("Auto-cancelled {} active appointment(s) for listing {} due to status transition to {}",
                activeAppointments.size(), listingId, newStatus);
    }

    // =========================================================================
    // 12. CÁC API TRUY VẤN DANH SÁCH (QUERIES)
    // =========================================================================
    @Transactional(readOnly = true)
    public PageResponse<AppointmentResponse> getMyBookings(String renterId, AppointmentStatus status, LocalDate date, int page, int size) {
        if (renterId == null || renterId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Specification<ViewingAppointment> spec = (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("listing", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("renterId"), renterId));
            predicates.add(cb.isTrue(root.get("active")));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (date != null) {
                predicates.add(cb.equal(root.get("appointmentDate"), date));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("appointmentDate"), Sort.Order.desc("startTime"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50), sort);
        Page<ViewingAppointment> pageResult = appointmentRepository.findAll(spec, pageable);
        return new PageResponse<>(pageResult.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public AppointmentResponse getBookingByListing(String renterId, String listingId) {
        if (renterId == null || renterId.isBlank()) {
            return null;
        }
        List<ViewingAppointment> list = appointmentRepository.findActiveByRenterIdAndListingId(
                renterId, listingId, Set.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED));
        return list.isEmpty() ? null : toResponse(list.get(0));
    }

    @Transactional(readOnly = true)
    public PageResponse<AppointmentResponse> getOwnerAppointments(
            String ownerId, String listingId, AppointmentStatus status, LocalDate date, int page, int size) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Specification<ViewingAppointment> spec = (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("listing", JoinType.INNER);
            }
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("ownerId"), ownerId));
            predicates.add(cb.isTrue(root.get("active")));
            if (listingId != null && !listingId.isBlank()) {
                predicates.add(cb.equal(root.get("listing").get("id"), listingId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (date != null) {
                predicates.add(cb.equal(root.get("appointmentDate"), date));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        var sort = Sort.by(Sort.Order.desc("appointmentDate"), Sort.Order.desc("startTime"));
        var pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50), sort);
        Page<ViewingAppointment> pageResult = appointmentRepository.findAll(spec, pageable);
        return new PageResponse<>(pageResult.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getOwnerAppointmentCounts(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            return Map.of();
        }
        Map<String, Long> counts = new HashMap<>();
        counts.put("ALL", appointmentRepository.countByOwnerIdAndActiveTrue(ownerId));
        for (AppointmentStatus s : AppointmentStatus.values()) {
            counts.put(s.name(), appointmentRepository.countByOwnerIdAndStatusAndActiveTrue(ownerId, s));
        }
        return counts;
    }

    // =========================================================================
    // 13. TÁC VỤ TỰ ĐỘNG DÀNH CHO SCHEDULER (AUTO-EXPIRE & AUTO-COMPLETE)
    // =========================================================================
    @Transactional
    public int autoExpirePendingAppointments() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        List<ViewingAppointment> expiredList = appointmentRepository.findExpiredPendingAppointments(today, now);
        for (ViewingAppointment apt : expiredList) {
            apt.setStatus(AppointmentStatus.EXPIRED);
            appointmentRepository.save(apt);
        }
        return expiredList.size();
    }

    @Transactional
    public int autoCompleteConfirmedAppointments() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();
        List<ViewingAppointment> pastList = appointmentRepository.findPastConfirmedAppointments(today, now);
        for (ViewingAppointment apt : pastList) {
            apt.setStatus(AppointmentStatus.COMPLETED);
            apt.setCompletedAt(Instant.now());
            appointmentRepository.save(apt);
        }
        return pastList.size();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================
    private ViewingAppointment getAppointmentForOwner(String ownerId, String appointmentId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        ViewingAppointment apt = appointmentRepository.findByIdWithListing(appointmentId)
                .orElseThrow(() -> new AppException(ListingErrorCode.APPOINTMENT_NOT_FOUND));
        if (!ownerId.equals(apt.getOwnerId())) {
            throw new AppException(ListingErrorCode.APPOINTMENT_FORBIDDEN);
        }
        return apt;
    }

    private ViewingSlot resolveSlotType(LocalTime start, LocalTime end) {
        if (start.isBefore(LocalTime.of(12, 1))) {
            return ViewingSlot.MORNING;
        } else if (start.isBefore(LocalTime.of(17, 1))) {
            return ViewingSlot.AFTERNOON;
        } else {
            return ViewingSlot.EVENING;
        }
    }

    private AppointmentResponse toResponse(ViewingAppointment va) {
        Listing l = va.getListing();
        String thumbnail = null;
        String addressStr = null;
        BigDecimal price = null;

        if (l != null) {
            price = l.getPriceAmount();
            if (l.getMedia() != null && !l.getMedia().isEmpty()) {
                ListingMedia coverMedia = l.getMedia().stream()
                        .filter(ListingMedia::isCover)
                        .findFirst()
                        .orElseGet(() -> l.getMedia().get(0));

                if (coverMedia != null) {
                    if (coverMedia.getStorageObject() != null && coverMedia.getStorageObject().getVisibility() == StorageVisibility.PUBLIC) {
                        thumbnail = "https://%s.s3.%s.amazonaws.com/%s"
                                .formatted(coverMedia.getStorageObject().getBucketName(),
                                        storageProperties.region(),
                                        coverMedia.getStorageObject().getObjectKey());
                    } else if (coverMedia.getMediaUrl() != null && (coverMedia.getMediaUrl().startsWith("http://") || coverMedia.getMediaUrl().startsWith("https://") || coverMedia.getMediaUrl().startsWith("/"))) {
                        thumbnail = coverMedia.getMediaUrl();
                    }
                }
            }
            Address addr = addressRepository.findByListingIdAndActiveTrue(l.getId()).orElse(null);
            if (addr != null) {
                addressStr = addr.getFullAddress();
            }
        }

        return AppointmentResponse.builder()
                .id(va.getId())
                .listingId(l != null ? l.getId() : null)
                .listingTitle(l != null ? l.getTitle() : null)
                .listingAddress(addressStr)
                .listingThumbnail(thumbnail)
                .listingPrice(price)
                .ownerId(va.getOwnerId())
                .renterId(va.getRenterId())
                .renterName(va.getRenterName())
                .renterPhone(va.getRenterPhone())
                .renterEmail(va.getRenterEmail())
                .visitorCount(va.getVisitorCount())
                .renterNote(va.getRenterNote())
                .appointmentDate(va.getAppointmentDate())
                .startTime(va.getStartTime())
                .endTime(va.getEndTime())
                .slotType(va.getSlotType())
                .status(va.getStatus())
                .ownerNote(va.getOwnerNote())
                .rejectReason(va.getRejectReason())
                .cancelledBy(va.getCancelledBy())
                .cancelReason(va.getCancelReason())
                .rescheduleRequested(va.getRescheduleRequested())
                .proposedDate(va.getProposedDate())
                .proposedStartTime(va.getProposedStartTime())
                .proposedEndTime(va.getProposedEndTime())
                .proposedSlotType(va.getProposedSlotType())
                .rescheduleReason(va.getRescheduleReason())
                .completedAt(va.getCompletedAt())
                .createdAt(va.getCreatedAt())
                .updatedAt(va.getUpdatedAt())
                .build();
    }
}
