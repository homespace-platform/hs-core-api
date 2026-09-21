package com.hs.api.controller.payment;

import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.payment.config.PaymentProofUploadProperties;
import com.hs.payment.dto.CreateUploadSessionResponse;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.service.PaymentProofUploadSessionService;
import com.hs.payment.service.PaymentProofUrlResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentProofUploadSessionControllerTest {

    @Mock
    private PaymentProofUploadSessionService sessionService;

    @Mock
    private PaymentProofUploadProperties properties;

    @Mock
    private PaymentProofUrlResolver urlResolver;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private PaymentProofUploadSessionController controller;

    @BeforeEach
    void setUp() {
        UserContext ctx = new UserContext("user-123", "tenant@example.com");
        UserContextHolder.set(ctx);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("createUploadSession uses PaymentProofUrlResolver and returns public upload URL")
    void testCreateUploadSession() {
        when(properties.publicBaseUrl()).thenReturn("https://13zp4kj8-8080.asse.devtunnels.ms");
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("http");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("172.31.34.19:8081");
        when(request.getHeader("Host")).thenReturn("172.31.34.19:8081");
        when(request.getServerName()).thenReturn("172.31.34.19");
        when(request.getServerPort()).thenReturn(8081);

        when(urlResolver.resolvePublicBaseUrl(
                "https://13zp4kj8-8080.asse.devtunnels.ms",
                "http",
                "172.31.34.19:8081",
                "172.31.34.19:8081",
                "172.31.34.19",
                8081
        )).thenReturn("https://13zp4kj8-8080.asse.devtunnels.ms");

        CreateUploadSessionResponse mockResponse = CreateUploadSessionResponse.builder()
                .sessionId("sess-1")
                .uploadPath("/u/payment-proof/raw-token-123")
                .uploadPageUrl("https://13zp4kj8-8080.asse.devtunnels.ms/u/payment-proof/raw-token-123")
                .expiresAt(Instant.now().plusSeconds(600))
                .status(UploadSessionStatus.CREATED)
                .build();

        when(sessionService.createSession("pay-1", "user-123", "https://13zp4kj8-8080.asse.devtunnels.ms"))
                .thenReturn(mockResponse);

        ApiResponse<CreateUploadSessionResponse> apiResponse = controller.createUploadSession("pay-1", request);

        assertNotNull(apiResponse);
        assertNotNull(apiResponse.getResult());
        assertEquals("https://13zp4kj8-8080.asse.devtunnels.ms/u/payment-proof/raw-token-123", apiResponse.getResult().uploadPageUrl());
        assertEquals("/u/payment-proof/raw-token-123", apiResponse.getResult().uploadPath());

        verify(urlResolver).resolvePublicBaseUrl(
                "https://13zp4kj8-8080.asse.devtunnels.ms",
                "http",
                "172.31.34.19:8081",
                "172.31.34.19:8081",
                "172.31.34.19",
                8081
        );
    }
}
