package com.hs.api.controller.payment;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentProofUploadSessionService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MobilePaymentProofUploadControllerTest {

    @Mock
    private PaymentProofUploadSessionService uploadSessionService;

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @InjectMocks
    private MobilePaymentProofUploadController controller;

    @Test
    @DisplayName("renderUploadPage returns mobile-upload view when session is valid")
    void testRenderUploadPage_valid() {
        String token = "valid-token";
        Instant expiresAt = Instant.now().plusSeconds(600);
        PaymentProofUploadSession session = PaymentProofUploadSession.builder()
                .id("sess-1")
                .paymentRequestId("pay-1")
                .tokenHash("hash-1")
                .status(UploadSessionStatus.CREATED)
                .expiresAt(expiresAt)
                .build();

        PaymentRequest payment = PaymentRequest.builder()
                .id("pay-1")
                .rentalRequestId("req-12345678")
                .transferReference("HS123456")
                .totalAmount(new BigDecimal("20300000"))
                .status(PaymentStatus.AWAITING_TRANSFER)
                .build();

        when(uploadSessionService.findValidSessionByRawToken(token)).thenReturn(session);
        when(paymentRequestRepository.findById("pay-1")).thenReturn(Optional.of(payment));

        Model model = new ExtendedModelMap();
        HttpServletResponse response = new MockHttpServletResponse();

        String view = controller.renderUploadPage(token, model, response);

        assertEquals("proof-upload/mobile-upload", view);
        assertEquals(token, model.getAttribute("token"));
        assertEquals("#REQ-1234", model.getAttribute("rentalRequestIdShort"));
        assertEquals("HS123456", model.getAttribute("transferReference"));
        assertTrue(((String) model.getAttribute("totalAmountFormatted")).contains("20.300.000"));
        assertEquals("no-store, max-age=0", response.getHeader("Cache-Control"));
    }

    @Test
    @DisplayName("renderUploadPage returns mobile-upload-error when session is null or expired")
    void testRenderUploadPage_invalidSession() {
        String token = "expired-token";
        when(uploadSessionService.findValidSessionByRawToken(token)).thenReturn(null);

        Model model = new ExtendedModelMap();
        HttpServletResponse response = new MockHttpServletResponse();

        String view = controller.renderUploadPage(token, model, response);

        assertEquals("proof-upload/mobile-upload-error", view);
        assertEquals("Phiên không hợp lệ", model.getAttribute("errorTitle"));
        assertNotNull(model.getAttribute("errorMessage"));
    }

    @Test
    @DisplayName("handleUpload successfully processes file and returns mobile-upload-success")
    void testHandleUpload_success() throws Exception {
        String token = "valid-token";
        byte[] content = "valid jpeg content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "receipt.jpg", "image/jpeg", content);

        Model model = new ExtendedModelMap();
        HttpServletResponse response = new MockHttpServletResponse();

        String view = controller.handleUpload(token, file, model, response);

        assertEquals("proof-upload/mobile-upload-success", view);
        verify(uploadSessionService).handleMobileUpload(
                eq(token),
                eq(content),
                eq("receipt.jpg"),
                eq("image/jpeg"),
                eq((long) content.length)
        );
    }

    @Test
    @DisplayName("handleUpload handles AppException and returns mobile-upload-error")
    void testHandleUpload_appException() throws Exception {
        String token = "valid-token";
        byte[] content = "content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "receipt.jpg", "image/jpeg", content);

        doThrow(new AppException(PaymentErrorCode.PAYMENT_PROOF_INVALID, "Dung lượng tệp không được vượt quá 15MB."))
                .when(uploadSessionService).handleMobileUpload(any(), any(), any(), any(), anyLong());

        Model model = new ExtendedModelMap();
        HttpServletResponse response = new MockHttpServletResponse();

        String view = controller.handleUpload(token, file, model, response);

        assertEquals("proof-upload/mobile-upload-error", view);
        assertEquals("Không thể tải chứng từ", model.getAttribute("errorTitle"));
        assertEquals("Dung lượng file không được vượt quá 15 MB.", model.getAttribute("errorMessage"));
    }

    @Test
    @DisplayName("handleMaxSizeExceeded returns mobile-upload-error with descriptive message")
    void testHandleMaxSizeExceeded() {
        MaxUploadSizeExceededException ex = new MaxUploadSizeExceededException(20 * 1024 * 1024);
        Model model = new ExtendedModelMap();
        HttpServletResponse response = new MockHttpServletResponse();

        String view = controller.handleMaxSizeExceeded(ex, model, response);

        assertEquals("proof-upload/mobile-upload-error", view);
        assertEquals("Dung lượng file quá lớn", model.getAttribute("errorTitle"));
        assertTrue(((String) model.getAttribute("errorMessage")).contains("15 MB"));
    }

    @Test
    @DisplayName("handleMultipartException returns mobile-upload-error with descriptive message")
    void testHandleMultipartException() {
        MultipartException ex = new MultipartException("Malformed request");
        Model model = new ExtendedModelMap();
        HttpServletResponse response = new MockHttpServletResponse();

        String view = controller.handleMultipartException(ex, model, response);

        assertEquals("proof-upload/mobile-upload-error", view);
        assertEquals("Lỗi tải tệp", model.getAttribute("errorTitle"));
    }
}
