package com.hs.listing.service.ai;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.ai.AiAppointmentQuery;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ViewingAppointmentRepository;
import com.hs.listing.service.ViewingAppointmentService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Always-on checks (no database): bad input and missing identity are rejected with fixed codes before any query
 * runs. The database behaviour is covered by {@link AiAppointmentQueryPostgresTest}.
 */
class AiAppointmentQueryValidationTest {
    final EntityManager em = mock(EntityManager.class);
    final ViewingAppointmentRepository appointments = mock(ViewingAppointmentRepository.class);
    final ListingRepository listings = mock(ListingRepository.class);
    final AiAppointmentQueryService service = new AiAppointmentQueryService(appointments, listings,
            mock(ViewingAppointmentService.class), em,
            Clock.fixed(Instant.parse("2026-10-14T03:30:00Z"), ViewingAppointmentService.VIETNAM_ZONE));

    static AiAppointmentQuery query(String role, List<String> status, String preset, String from, String to,
                                    String size) {
        return new AiAppointmentQuery(role, status, preset, from, to, null, null, null, null, null, null, null, null, size);
    }

    int code(Runnable action) {
        return assertThrows(AppException.class, action::run).getCode();
    }

    @Test
    void identityIsRequiredEverywhere() {
        int unauthenticated = ErrorCode.UNAUTHENTICATED.getCode();
        assertEquals(unauthenticated, code(() -> service.summarize(null, null)));
        assertEquals(unauthenticated, code(() -> service.search("", null)));
        assertEquals(unauthenticated, code(() -> service.detail(" ", "aaaaaaaa-0000-0000-0000-000000000001")));
        assertEquals(unauthenticated, code(() -> service.availability(null, "aaaaaaaa-0000-0000-0000-000000000001", null)));
        verifyNoInteractions(em, appointments, listings);
    }

    @Test
    void invalidInputIsRejectedBeforeAnyQuery() {
        int invalid = ListingErrorCode.APPOINTMENT_AI_QUERY_INVALID.getCode();
        assertEquals(invalid, code(() -> service.search("u", query("ADMIN", null, null, null, null, null))));
        assertEquals(invalid, code(() -> service.search("u", query(null, List.of("DELETED"), null, null, null, null))));
        assertEquals(invalid, code(() -> service.search("u", query(null, null, "TODAY", "2026-10-14", "2026-10-14", null))));
        assertEquals(invalid, code(() -> service.search("u", query(null, null, null, "2026-10-14", null, null))));
        assertEquals(invalid, code(() -> service.search("u", query(null, null, null, "2026-01-01", "2027-02-01", null))));
        assertEquals(invalid, code(() -> service.summarize("u", query(null, null, "NEXT_DECADE", null, null, null))));
        assertEquals(invalid, code(() -> service.search("u", query(null, null, null, null, null, "9999"))));
        assertEquals(invalid, code(() -> service.detail("u", "not-a-uuid")));
        assertEquals(invalid, code(() -> service.detail("u", null)));
        assertEquals(invalid, code(() -> service.availability("u", "x'; drop table listings;--", null)));
        assertEquals(invalid, code(() -> service.availability("u", "aaaaaaaa-0000-0000-0000-000000000001", "tomorrow")));
        verifyNoInteractions(em, appointments, listings);
    }
}
