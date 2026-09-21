package com.hs.api.controller.payment;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.payment.dto.CreateUploadSessionResponse;
import com.hs.payment.dto.UploadSessionStatusResponse;
import com.hs.payment.service.PaymentProofUploadSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payment-requests/{paymentRequestId}/proof-upload-sessions")
@RequiredArgsConstructor
public class PaymentProofUploadSessionController {

    private final PaymentProofUploadSessionService proofUploadSessionService;

    private String requireUserId() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null || ctx.userId() == null || ctx.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return ctx.userId();
    }

    @PostMapping
    public ApiResponse<CreateUploadSessionResponse> createUploadSession(
            @PathVariable String paymentRequestId,
            HttpServletRequest request
    ) {
        String actorId = requireUserId();
        String baseUrl = resolveBaseUrl(request);
        CreateUploadSessionResponse response = proofUploadSessionService.createSession(paymentRequestId, actorId, baseUrl);
        return ApiResponse.<CreateUploadSessionResponse>builder()
                .message("Tạo phiên tải chứng từ từ điện thoại thành công")
                .result(response)
                .build();
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (request == null) {
            return "http://localhost:8080";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (("http".equalsIgnoreCase(proto) && port != 80) || ("https".equalsIgnoreCase(proto) && port != 443)) {
                host = host + ":" + port;
            }
        }
        // Validate host against host-header injection
        if (!host.matches("^[a-zA-Z0-9.:\\-_]+$")) {
            host = "localhost:8080";
        }
        return proto + "://" + host;
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<UploadSessionStatusResponse> getUploadSessionStatus(
            @PathVariable String paymentRequestId,
            @PathVariable String sessionId
    ) {
        String actorId = requireUserId();
        UploadSessionStatusResponse response = proofUploadSessionService.getSessionStatus(paymentRequestId, sessionId, actorId);
        return ApiResponse.<UploadSessionStatusResponse>builder()
                .result(response)
                .build();
    }
}
