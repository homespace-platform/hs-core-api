package com.hs.api.controller.payment;

import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentProofUploadSessionService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import com.hs.common.advice.entity.AppException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@Slf4j
@Controller
@RequestMapping("/u/payment-proof")
@RequiredArgsConstructor
public class MobilePaymentProofUploadController {

    private final PaymentProofUploadSessionService uploadSessionService;
    private final PaymentRequestRepository paymentRequestRepository;

    private void applySecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline';");
    }

    private String formatVnd(java.math.BigDecimal amount) {
        if (amount == null) return "0 ₫";
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.of("vi", "VN"));
        symbols.setGroupingSeparator('.');
        DecimalFormat df = new DecimalFormat("#,###", symbols);
        return df.format(amount) + " ₫";
    }

    @GetMapping("/{token}")
    public String renderUploadPage(
            @PathVariable("token") String token,
            Model model,
            HttpServletResponse response
    ) {
        applySecurityHeaders(response);

        PaymentProofUploadSession session = uploadSessionService.findValidSessionByRawToken(token);
        if (session == null) {
            model.addAttribute("errorTitle", "Phiên không hợp lệ");
            model.addAttribute("errorMessage", "Phiên tải chứng từ này không tồn tại hoặc đã hết hạn. Vui lòng tạo mã QR mới trên máy tính.");
            return "proof-upload/mobile-upload-error";
        }

        // Nếu session đã gửi thành công trước đó (người dùng reload lại trang)
        if (session.getStatus() == UploadSessionStatus.CONSUMED) {
            return "proof-upload/mobile-upload-success";
        }

        PaymentRequest payment = paymentRequestRepository.findById(session.getPaymentRequestId()).orElse(null);
        if (payment == null) {
            model.addAttribute("errorTitle", "Không tìm thấy yêu cầu");
            model.addAttribute("errorMessage", "Yêu cầu thanh toán không hợp lệ.");
            return "proof-upload/mobile-upload-error";
        }

        long remainingSeconds = Math.max(0, Duration.between(Instant.now(), session.getExpiresAt()).toSeconds());
        long m = remainingSeconds / 60;
        long s = remainingSeconds % 60;
        String expiresInText = String.format("%02d:%02d", m, s);

        String rentalRequestIdShort = "#" + (payment.getRentalRequestId().length() > 8
                ? payment.getRentalRequestId().substring(0, 8).toUpperCase()
                : payment.getRentalRequestId().toUpperCase());

        model.addAttribute("token", token);
        model.addAttribute("rentalRequestIdShort", rentalRequestIdShort);
        model.addAttribute("transferReference", payment.getTransferReference());
        model.addAttribute("totalAmountFormatted", formatVnd(payment.getTotalAmount()));
        model.addAttribute("remainingSeconds", remainingSeconds);
        model.addAttribute("expiresInText", expiresInText);

        return "proof-upload/mobile-upload";
    }

    @PostMapping("/{token}")
    public String handleUpload(
            @PathVariable("token") String token,
            @RequestParam("file") MultipartFile file,
            Model model,
            HttpServletResponse response
    ) {
        applySecurityHeaders(response);
        String correlationId = java.util.UUID.randomUUID().toString().substring(0, 8);

        try {
            uploadSessionService.handleMobileUpload(
                    token,
                    file.getBytes(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize()
            );
            return "proof-upload/mobile-upload-success";
        } catch (AppException e) {
            log.warn("[{}] Mobile upload rejected for token: code={}, message={}", correlationId, e.getCode(), e.getErrorMessage());
            model.addAttribute("errorTitle", "Không thể tải chứng từ");
            model.addAttribute("errorMessage", resolveFriendlyErrorMessage(e));
            return "proof-upload/mobile-upload-error";
        } catch (Exception e) {
            log.error("[{}] Mobile upload unexpected error for token: {}", correlationId, e.getMessage(), e);
            model.addAttribute("errorTitle", "Không thể tải chứng từ");
            model.addAttribute("errorMessage", "Không thể tải chứng từ lên. Vui lòng thử lại hoặc tạo mã QR mới.");
            return "proof-upload/mobile-upload-error";
        }
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleMaxSizeExceeded(MaxUploadSizeExceededException e, Model model, HttpServletResponse response) {
        log.warn("Mobile upload size exceeded: {}", e.getMessage());
        applySecurityHeaders(response);
        model.addAttribute("errorTitle", "Dung lượng file quá lớn");
        model.addAttribute("errorMessage", "Dung lượng file không được vượt quá 15 MB. Vui lòng chọn ảnh có dung lượng nhỏ hơn.");
        return "proof-upload/mobile-upload-error";
    }

    @ExceptionHandler(MultipartException.class)
    public String handleMultipartException(MultipartException e, Model model, HttpServletResponse response) {
        log.warn("Mobile multipart exception: {}", e.getMessage());
        applySecurityHeaders(response);
        model.addAttribute("errorTitle", "Lỗi tải tệp");
        model.addAttribute("errorMessage", "Không thể tiếp nhận tệp tải lên. Vui lòng thử lại với định dạng JPG, PNG hoặc PDF.");
        return "proof-upload/mobile-upload-error";
    }

    @ExceptionHandler(AppException.class)
    public String handleAppException(AppException e, Model model, HttpServletResponse response) {
        log.warn("Mobile upload controller AppException: code={}, message={}", e.getCode(), e.getErrorMessage());
        applySecurityHeaders(response);
        model.addAttribute("errorTitle", "Không thể tải chứng từ");
        model.addAttribute("errorMessage", resolveFriendlyErrorMessage(e));
        return "proof-upload/mobile-upload-error";
    }

    @ExceptionHandler(Exception.class)
    public String handleGeneralException(Exception e, Model model, HttpServletResponse response) {
        log.error("Unhandled exception in MobilePaymentProofUploadController: ", e);
        applySecurityHeaders(response);
        model.addAttribute("errorTitle", "Không thể tải chứng từ");
        model.addAttribute("errorMessage", "Không thể tải chứng từ lên. Vui lòng thử lại hoặc tạo mã QR mới.");
        return "proof-upload/mobile-upload-error";
    }

    private String resolveFriendlyErrorMessage(AppException e) {
        if (e == null) {
            return "Không thể tải chứng từ lên. Vui lòng thử lại hoặc tạo mã QR mới.";
        }
        int code = e.getCode();
        if (code == PaymentErrorCode.PROOF_SESSION_EXPIRED.getCode()) {
            return "Phiên tải chứng từ đã hết hạn.";
        }
        if (code == PaymentErrorCode.PROOF_SESSION_INVALID.getCode()) {
            return "Phiên tải chứng từ không hợp lệ hoặc đã kết thúc.";
        }
        if (code == PaymentErrorCode.PROOF_SESSION_NOT_FOUND.getCode()) {
            return "Phiên tải chứng từ không tồn tại hoặc đã bị hủy.";
        }
        if (code == PaymentErrorCode.PAYMENT_PROOF_REQUIRED.getCode()) {
            return "Vui lòng chọn ảnh hoặc tệp chứng từ chuyển khoản.";
        }
        if (code == PaymentErrorCode.PAYMENT_PROOF_INVALID.getCode()) {
            String msg = e.getErrorMessage();
            if (msg != null && (msg.contains("15") || msg.toLowerCase().contains("dung lượng"))) {
                return "Dung lượng file không được vượt quá 15 MB.";
            }
            if (msg != null && (msg.contains("JPG") || msg.toLowerCase().contains("định dạng") || msg.toLowerCase().contains("loại"))) {
                return "Chỉ chấp nhận JPG, PNG, WebP hoặc PDF.";
            }
            return "Chứng từ không hợp lệ. Vui lòng chọn ảnh (JPG, PNG, WebP) hoặc PDF.";
        }
        if (code == PaymentErrorCode.PAYMENT_ALREADY_CONFIRMED.getCode()) {
            return "Yêu cầu thanh toán này đã được xác nhận hoàn tất.";
        }
        return "Không thể tải chứng từ lên. Vui lòng thử lại hoặc tạo mã QR mới.";
    }
}
