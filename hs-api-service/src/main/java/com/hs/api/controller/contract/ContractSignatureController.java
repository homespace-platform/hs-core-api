package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.dto.signature.CertificateOptionDto;
import com.hs.contract.dto.signature.InitiateSignatureRequest;
import com.hs.contract.dto.signature.SignatureRequestDto;
import com.hs.contract.dto.signature.SignatureStateDto;
import com.hs.contract.service.SmartCaSignatureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API endpoints cho ký số SmartCA.
 *
 * <p>Tất cả endpoints đều yêu cầu authentication (xử lý bởi filter chain).
 * Quyền truy cập theo contractId được kiểm tra trong service layer.</p>
 */
@Slf4j
@RestController
@RequestMapping({"/contracts/{contractId}/signatures", "/api/v1/contracts/{contractId}/signatures"})
@RequiredArgsConstructor
public class ContractSignatureController {

    private final SmartCaSignatureService signatureService;

    /**
     * GET /contracts/{contractId}/signatures/state
     * Trả về trạng thái ký số hiện tại của hợp đồng cho người dùng đang đăng nhập.
     */
    @GetMapping("/state")
    public ApiResponse<SignatureStateDto> getSignatureState(@PathVariable String contractId) {
        return ApiResponse.<SignatureStateDto>builder()
                .result(signatureService.getSignatureState(contractId, requireUserId()))
                .build();
    }

    /**
     * GET /contracts/{contractId}/signatures/certificates
     * Trả về danh sách chứng thư SmartCA của người dùng (gọi VNPT API).
     */
    @GetMapping("/certificates")
    public ApiResponse<List<CertificateOptionDto>> getCertificates(@PathVariable String contractId) {
        return ApiResponse.<List<CertificateOptionDto>>builder()
                .result(signatureService.getSigningCertificates(contractId, requireUserId()))
                .build();
    }

    /**
     * POST /contracts/{contractId}/signatures/initiate
     * Khởi tạo yêu cầu ký số. Idempotent khi request còn hiệu lực.
     */
    @PostMapping("/initiate")
    public ApiResponse<SignatureRequestDto> initiateSignature(
            @PathVariable String contractId,
            @RequestBody @Valid InitiateSignatureRequest request
    ) {
        return ApiResponse.<SignatureRequestDto>builder()
                .message("Yêu cầu ký số đã được gửi — vui lòng mở ứng dụng VNPT SmartCA để xác nhận")
                .result(signatureService.initiateSignature(contractId, requireUserId(), request))
                .build();
    }

    /**
     * GET /contracts/{contractId}/signatures/{requestId}
     * Trả về trạng thái một request cụ thể.
     */
    @GetMapping("/{requestId}")
    public ApiResponse<SignatureRequestDto> getSignatureRequest(
            @PathVariable String contractId,
            @PathVariable String requestId
    ) {
        return ApiResponse.<SignatureRequestDto>builder()
                .result(signatureService.getSignatureRequest(contractId, requestId, requireUserId()))
                .build();
    }

    /**
     * POST /contracts/{contractId}/signatures/{requestId}/refresh
     * Poll VNPT và cập nhật trạng thái request.
     */
    @PostMapping("/{requestId}/refresh")
    public ApiResponse<SignatureRequestDto> refreshSignatureRequest(
            @PathVariable String contractId,
            @PathVariable String requestId
    ) {
        return ApiResponse.<SignatureRequestDto>builder()
                .result(signatureService.refreshSignatureRequest(contractId, requestId, requireUserId()))
                .build();
    }

    /**
     * POST /contracts/{contractId}/signatures/{requestId}/retry
     * Thử lại sau khi bị từ chối/hết hạn/lỗi.
     */
    @PostMapping("/{requestId}/retry")
    public ApiResponse<SignatureRequestDto> retrySignature(
            @PathVariable String contractId,
            @PathVariable String requestId
    ) {
        return ApiResponse.<SignatureRequestDto>builder()
                .message("Đã gửi lại yêu cầu ký số — vui lòng mở ứng dụng VNPT SmartCA để xác nhận")
                .result(signatureService.retrySignature(contractId, requestId, requireUserId()))
                .build();
    }

    private String requireUserId() {
        UserContext ctx = UserContextHolder.get();
        if (ctx == null || ctx.userId() == null || ctx.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return ctx.userId();
    }
}
