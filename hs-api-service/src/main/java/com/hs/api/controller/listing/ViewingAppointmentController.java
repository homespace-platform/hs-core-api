package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.*;
import com.hs.listing.dto.response.AppointmentResponse;
import com.hs.listing.dto.response.ListingAvailabilityResponse;
import com.hs.listing.model.constant.AppointmentStatus;
import com.hs.listing.service.ViewingAppointmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/appointments")
@RequiredArgsConstructor
@Validated
public class ViewingAppointmentController {

    private final ViewingAppointmentService appointmentService;

    // =========================================================================
    // 1. DÀNH CHO KHÁCH HÀNG (RENTER) & XEM LỊCH KHẢ DỤNG (AVAILABILITY)
    // =========================================================================

    @GetMapping("/availability/{listingId}")
    public ApiResponse<ListingAvailabilityResponse> getAvailability(
            @PathVariable String listingId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        String currentUserId = optionalUserId();
        return ApiResponse.<ListingAvailabilityResponse>builder()
                .result(appointmentService.getAvailability(listingId, date, currentUserId))
                .build();
    }

    @PostMapping
    public ApiResponse<AppointmentResponse> createAppointment(@RequestBody @Valid CreateAppointmentRequest req) {
        UserContext context = requireUserContext();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Gửi yêu cầu đặt lịch xem nhà thành công. Vui lòng chờ chủ nhà xác nhận.")
                .result(appointmentService.createAppointment(context.userId(), context.email(), req))
                .build();
    }

    @GetMapping("/my-bookings")
    public PageResponse<AppointmentResponse> getMyBookings(
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        String renterId = requireUserId();
        return appointmentService.getMyBookings(renterId, status, date, page, size);
    }

    @GetMapping("/my-bookings/by-listing/{listingId}")
    public ApiResponse<AppointmentResponse> getMyBookingByListing(@PathVariable String listingId) {
        String renterId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .result(appointmentService.getBookingByListing(renterId, listingId))
                .build();
    }

    @PutMapping("/{id}/reschedule")
    public ApiResponse<AppointmentResponse> requestReschedule(
            @PathVariable String id,
            @RequestBody @Valid RescheduleAppointmentRequest req) {
        String renterId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã gửi yêu cầu đổi lịch hẹn đến chủ nhà")
                .result(appointmentService.requestReschedule(renterId, id, req))
                .build();
    }

    @PutMapping("/{id}/cancel")
    public ApiResponse<AppointmentResponse> cancelByRenter(
            @PathVariable String id,
            @RequestBody(required = false) CancelAppointmentRequest req) {
        String renterId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã hủy lịch xem nhà")
                .result(appointmentService.cancelByRenter(renterId, id, req))
                .build();
    }

    // =========================================================================
    // 2. DÀNH CHO CHỦ NHÀ (OWNER) TRÊN DASHBOARD
    // =========================================================================

    @GetMapping("/owner")
    public PageResponse<AppointmentResponse> getOwnerAppointments(
            @RequestParam(required = false) String listingId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int size) {
        String ownerId = requireUserId();
        return appointmentService.getOwnerAppointments(ownerId, listingId, status, date, page, size);
    }

    @GetMapping("/owner/counts")
    public ApiResponse<Map<String, Long>> getOwnerAppointmentCounts() {
        String ownerId = requireUserId();
        return ApiResponse.<Map<String, Long>>builder()
                .result(appointmentService.getOwnerAppointmentCounts(ownerId))
                .build();
    }

    @PutMapping("/{id}/approve")
    public ApiResponse<AppointmentResponse> approveAppointment(
            @PathVariable String id,
            @RequestBody(required = false) ApproveAppointmentRequest req) {
        String ownerId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã chấp nhận lịch xem nhà. Khung giờ này đã được khóa lại.")
                .result(appointmentService.approveAppointment(ownerId, id, req))
                .build();
    }

    @PutMapping("/{id}/reject")
    public ApiResponse<AppointmentResponse> rejectAppointment(
            @PathVariable String id,
            @RequestBody @Valid RejectAppointmentRequest req) {
        String ownerId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã từ chối lịch xem nhà")
                .result(appointmentService.rejectAppointment(ownerId, id, req))
                .build();
    }

    @PutMapping("/{id}/reschedule/approve")
    public ApiResponse<AppointmentResponse> approveReschedule(@PathVariable String id) {
        String ownerId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã chấp nhận thay đổi thời gian xem nhà. Khung giờ mới đã được khóa.")
                .result(appointmentService.approveReschedule(ownerId, id))
                .build();
    }

    @PutMapping("/{id}/reschedule/reject")
    public ApiResponse<AppointmentResponse> rejectReschedule(
            @PathVariable String id,
            @RequestBody @Valid RejectAppointmentRequest req) {
        String ownerId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã từ chối yêu cầu đổi lịch của khách hàng")
                .result(appointmentService.rejectReschedule(ownerId, id, req))
                .build();
    }

    @PutMapping("/{id}/owner-cancel")
    public ApiResponse<AppointmentResponse> cancelByOwner(
            @PathVariable String id,
            @RequestBody @Valid CancelAppointmentRequest req) {
        String ownerId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã hủy lịch xem nhà")
                .result(appointmentService.cancelByOwner(ownerId, id, req))
                .build();
    }

    @PutMapping("/{id}/complete")
    public ApiResponse<AppointmentResponse> completeAppointment(@PathVariable String id) {
        String ownerId = requireUserId();
        return ApiResponse.<AppointmentResponse>builder()
                .message("Đã xác nhận hoàn thành buổi xem nhà")
                .result(appointmentService.completeAppointment(ownerId, id))
                .build();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private UserContext requireUserContext() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return context;
    }

    private String requireUserId() {
        return requireUserContext().userId();
    }

    private String optionalUserId() {
        UserContext context = UserContextHolder.get();
        return (context != null && context.userId() != null && !context.userId().isBlank())
                ? context.userId() : null;
    }
}
