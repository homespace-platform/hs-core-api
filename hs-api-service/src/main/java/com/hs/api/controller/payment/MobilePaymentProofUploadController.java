package com.hs.api.controller.payment;

import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentProofUploadSessionService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
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
            model.addAttribute("errorMessage", "Phiên tải chứng từ này không tồn tại, đã hết hạn hoặc đã được sử dụng.");
            return "proof-upload/mobile-upload-error";
        }

        PaymentRequest payment = paymentRequestRepository.findById(session.getPaymentRequestId()).orElse(null);
        if (payment == null) {
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

        try {
            uploadSessionService.handleMobileUpload(
                    token,
                    file.getBytes(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize()
            );
            return "proof-upload/mobile-upload-success";
        } catch (Exception e) {
            log.warn("Mobile upload failed for token: {}", e.getMessage());
            model.addAttribute("errorMessage", e.getMessage());
            return "proof-upload/mobile-upload-error";
        }
    }
}
