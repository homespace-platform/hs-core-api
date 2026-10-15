package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.listing.dto.ai.AiAppointmentDtos.Availability;
import com.hs.listing.dto.ai.AiAppointmentDtos.Detail;
import com.hs.listing.dto.ai.AiAppointmentDtos.SearchResponse;
import com.hs.listing.dto.ai.AiAppointmentDtos.SummaryResponse;
import com.hs.listing.dto.ai.AiAppointmentQuery;
import com.hs.listing.service.ai.AiAppointmentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only viewing-appointment queries for the AI assistant. The caller is always the authenticated user of the
 * Gateway-verified context (there is no user/owner/renter parameter) and nothing here changes state. Mapped as
 * {@code /appointments/ai/**}: the Gateway strips {@code /api/v1}, and the path never collides with the existing
 * {@code PUT /appointments/{id}/...} or public {@code GET /appointments/availability/{listingId}} routes. Unlike
 * the public availability route, this one requires a signed-in user.
 */
@RestController
@RequestMapping({"/appointments/ai", "/api/v1/appointments/ai"})
@RequiredArgsConstructor
public class AppointmentAiController {
    private final AiAppointmentQueryService service;

    @GetMapping("/summary")
    public ApiResponse<SummaryResponse> summary(@ModelAttribute AiAppointmentQuery query) {
        return ok(service.summarize(userId(), query));
    }

    @GetMapping("/search")
    public ApiResponse<SearchResponse> search(@ModelAttribute AiAppointmentQuery query) {
        return ok(service.search(userId(), query));
    }

    @GetMapping("/items/{appointmentId}")
    public ApiResponse<Detail> detail(@PathVariable String appointmentId) {
        return ok(service.detail(userId(), appointmentId));
    }

    @GetMapping("/listings/{listingId}/availability")
    public ApiResponse<Availability> availability(@PathVariable String listingId,
                                                  @RequestParam(required = false) String date,
                                                  @RequestParam(required = false) String datePreset,
                                                  @RequestParam(required = false) String weekday,
                                                  @RequestParam(required = false) String week) {
        return ok(service.availability(userId(), listingId, date, datePreset, weekday, week));
    }

    private static <T> ApiResponse<T> ok(T result) {
        return ApiResponse.<T>builder().result(result).build();
    }

    private static String userId() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return context.userId();
    }
}
