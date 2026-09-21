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
    private final com.hs.payment.config.PaymentProofUploadProperties proofUploadProperties;
    private final com.hs.payment.service.PaymentProofUrlResolver urlResolver;

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
        String proto = request != null ? request.getHeader("X-Forwarded-Proto") : null;
        String fwdHost = request != null ? request.getHeader("X-Forwarded-Host") : null;
        String hostHeader = request != null ? request.getHeader("Host") : null;
        String serverName = request != null ? request.getServerName() : null;
        int serverPort = request != null ? request.getServerPort() : 0;

        String baseUrl = urlResolver.resolvePublicBaseUrl(
                proofUploadProperties.publicBaseUrl(),
                proto,
                fwdHost,
                hostHeader,
                serverName,
                serverPort
        );

        CreateUploadSessionResponse response = proofUploadSessionService.createSession(paymentRequestId, actorId, baseUrl);
        return ApiResponse.<CreateUploadSessionResponse>builder()
                .message("Tạo phiên tải chứng từ từ điện thoại thành công")
                .result(response)
                .build();
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
