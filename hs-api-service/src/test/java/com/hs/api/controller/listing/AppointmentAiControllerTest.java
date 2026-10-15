package com.hs.api.controller.listing;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.listing.dto.ai.AiAppointmentQuery;
import com.hs.listing.service.ai.AiAppointmentQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppointmentAiControllerTest {
    final AiAppointmentQueryService service = mock(AiAppointmentQueryService.class);
    final AppointmentAiController controller = new AppointmentAiController(service);

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void everyEndpointRequiresTheSignedInUserAndNeverReachesTheService() {
        AiAppointmentQuery query = new AiAppointmentQuery(null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        for (Runnable call : new Runnable[]{() -> controller.summary(query), () -> controller.search(query),
                () -> controller.detail("aaaaaaaa-0000-0000-0000-000000000001"),
                () -> controller.availability("aaaaaaaa-0000-0000-0000-000000000001", null, null, null, null)}) {
            AppException error = assertThrows(AppException.class, call::run);
            assertEquals(ErrorCode.UNAUTHENTICATED.getCode(), error.getCode());
        }
        UserContextHolder.set(new UserContext(" ", null));
        assertThrows(AppException.class, () -> controller.summary(query));
        verifyNoInteractions(service);
    }

    @Test
    void identityComesFromTheContextAndNotFromTheRequest() {
        UserContextHolder.set(new UserContext("caller-1", "c@example.test"));
        AiAppointmentQuery query = new AiAppointmentQuery("BOTH", null, null, null, null, null, null, null, null, null,
                null, null, null, null);
        controller.search(query);
        controller.detail("aaaaaaaa-0000-0000-0000-000000000001");
        verify(service).search("caller-1", query);
        verify(service).detail("caller-1", "aaaaaaaa-0000-0000-0000-000000000001");
        // the query record has no user, owner or renter field at all
        assertTrue(Arrays.stream(AiAppointmentQuery.class.getRecordComponents())
                .noneMatch(c -> c.getName().toLowerCase().matches(".*(user|owner|renter).*")));
    }

    @Test
    void controllerIsReadOnlyAndMappedUnderTheGatewayRewrittenPath() {
        String[] base = AppointmentAiController.class.getAnnotation(RequestMapping.class).value();
        assertArrayEquals(new String[]{"/appointments/ai", "/api/v1/appointments/ai"}, base);
        for (Method method : AppointmentAiController.class.getDeclaredMethods()) {
            if (method.isSynthetic() || java.lang.reflect.Modifier.isPrivate(method.getModifiers())) continue;
            assertNotNull(method.getAnnotation(GetMapping.class), method.getName() + " must be GET only");
            assertNull(method.getAnnotation(PutMapping.class));
        }
    }
}
