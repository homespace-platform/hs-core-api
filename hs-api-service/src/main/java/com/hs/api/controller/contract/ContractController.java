package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.contract.dto.request.CreateContractDraftRequest;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.dto.response.ContractResponse;
import com.hs.contract.dto.response.ContractRevisionResponse;
import com.hs.contract.dto.response.ContractTemplateResponse;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.service.ContractService;
import com.hs.contract.service.ContractTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/contracts", "/api/v1/contracts"})
@RequiredArgsConstructor
@Validated
public class ContractController {

    private final ContractService contractService;
    private final ContractTemplateService templateService;

    /**
     * Lấy các mẫu hợp đồng đã xuất bản phù hợp với BĐS của yêu cầu thuê
     */
    @GetMapping("/applicable-templates")
    public ApiResponse<List<ContractTemplateResponse>> getApplicableTemplates(
            @RequestParam String rentalRequestId
    ) {
        return ApiResponse.<List<ContractTemplateResponse>>builder()
                .result(templateService.getApplicableTemplates(rentalRequestId))
                .build();
    }

    /**
     * Tạo bản nháp hợp đồng từ RentalRequest đã duyệt và mẫu hợp đồng đã chọn
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractResponse> createDraft(
            @Valid @RequestBody CreateContractDraftRequest request
    ) {
        return ApiResponse.<ContractResponse>builder()
                .message("Tạo bản nháp hợp đồng thành công")
                .result(contractService.createDraft(request))
                .build();
    }

    /**
     * Danh sách hợp đồng liên quan tới user đang đăng nhập (phân trang PageResponse)
     */
    @GetMapping
    public PageResponse<ContractResponse> getMyContracts(
            @RequestParam(required = false) ContractStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        String userId = requireUserId();
        return contractService.getContractsForCurrentUser(userId, status, page, size);
    }

    /**
     * Chi tiết hợp đồng
     */
    @GetMapping("/{contractId}")
    public ApiResponse<ContractResponse> getContract(@PathVariable String contractId) {
        return ApiResponse.<ContractResponse>builder()
                .result(contractService.getContract(contractId))
                .build();
    }

    /**
     * Lấy dữ liệu snapshot của bản chụp revision hiện tại
     */
    @GetMapping("/{contractId}/revision")
    public ApiResponse<ContractRevisionResponse> getLatestRevision(@PathVariable String contractId) {
        return ApiResponse.<ContractRevisionResponse>builder()
                .result(contractService.getLatestRevision(contractId))
                .build();
    }

    /**
     * Cập nhật / bổ sung thông tin thỏa thuận hợp đồng (tạo revision mới)
     */
    @PatchMapping("/{contractId}/revision")
    public ApiResponse<ContractRevisionResponse> updateRevision(
            @PathVariable String contractId,
            @RequestBody UpdateContractRevisionRequest request
    ) {
        return ApiResponse.<ContractRevisionResponse>builder()
                .message("Cập nhật thỏa thuận hợp đồng thành công")
                .result(contractService.updateRevision(contractId, request))
                .build();
    }

    /**
     * Kích hoạt sinh tài liệu xem trước (DOCX + PDF) từ revision hiện tại
     */
    @PostMapping("/{contractId}/previews")
    public ApiResponse<ContractDocumentResponse> triggerPreview(@PathVariable String contractId) {
        return ApiResponse.<ContractDocumentResponse>builder()
                .message("Đã yêu cầu kết xuất tài liệu hợp đồng")
                .result(contractService.triggerPreview(contractId))
                .build();
    }

    /**
     * Lấy danh sách tài liệu đã sinh của hợp đồng
     */
    @GetMapping("/{contractId}/documents")
    public ApiResponse<List<ContractDocumentResponse>> getDocuments(@PathVariable String contractId) {
        return ApiResponse.<List<ContractDocumentResponse>>builder()
                .result(contractService.getDocumentsByContract(contractId))
                .build();
    }

    /**
     * Lấy chi tiết tài liệu và URL xem/tải
     */
    @GetMapping("/documents/{documentId}")
    public ApiResponse<ContractDocumentResponse> getDocument(@PathVariable String documentId) {
        return ApiResponse.<ContractDocumentResponse>builder()
                .result(contractService.getDocument(documentId))
                .build();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    private UserContext requireUserContext() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.userId() == null || context.userId().isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        return context;
    }

    private String requireUserId() {
        return requireUserContext().userId();
    }
}
