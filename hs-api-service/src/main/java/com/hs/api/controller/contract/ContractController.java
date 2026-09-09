package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.contract.dto.request.CreateContractDraftRequest;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.dto.request.CreateTemplateVersionRequest;
import com.hs.contract.dto.request.UpdateContractRevisionRequest;
import com.hs.contract.dto.request.UpdateContractTemplateRequest;
import com.hs.user.service.kyc.KycGateService;
import com.hs.contract.dto.response.ContractCompletenessResponse;
import com.hs.contract.dto.response.ContractDocumentResponse;
import com.hs.contract.dto.response.ContractResponse;
import com.hs.contract.dto.response.ContractRevisionResponse;
import com.hs.contract.dto.response.ContractTemplateResponse;
import com.hs.contract.dto.response.ContractTemplateVersionResponse;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.model.constant.ContractTemplateStatus;
import com.hs.contract.service.ContractService;
import com.hs.contract.service.ContractTemplateService;
import com.hs.listing.model.constant.ListingCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final KycGateService kycGateService;

    // =========================================================================
    // TỪ ĐIỂN MÃ TRƯỜNG & MẪU HỢP ĐỒNG (CHỦ NHÀ)
    // =========================================================================

    @GetMapping("/template-fields")
    public ApiResponse<List<TemplateFieldDefinition>> getCatalogFields() {
        return ApiResponse.<List<TemplateFieldDefinition>>builder()
                .result(templateService.getCatalogFields())
                .build();
    }

    /** Mẫu hệ thống đã xuất bản — chỉ xem. */
    @GetMapping("/templates/system")
    public ApiResponse<List<ContractTemplateResponse>> listSystemTemplates(
            @RequestParam(required = false) ListingCategory category
    ) {
        return ApiResponse.<List<ContractTemplateResponse>>builder()
                .result(templateService.listPublishedSystemTemplates(category))
                .build();
    }

    /** Mẫu do chủ nhà đang đăng nhập sở hữu. */
    @GetMapping("/templates/mine")
    public PageResponse<ContractTemplateResponse> listMyTemplates(
            @RequestParam(required = false) ContractTemplateStatus status,
            @RequestParam(required = false) ListingCategory category,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return templateService.listMyTemplates(requireUserId(), status, category, page, size);
    }

    @PostMapping("/templates/mine")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractTemplateResponse> createMyTemplate(
            @Valid @RequestBody CreateContractTemplateRequest request
    ) {
        return ApiResponse.<ContractTemplateResponse>builder()
                .message("Tạo mẫu hợp đồng thành công")
                .result(templateService.createMyTemplate(requireUserId(), request))
                .build();
    }

    @GetMapping("/templates/{templateId}")
    public ApiResponse<ContractTemplateResponse> getTemplate(@PathVariable String templateId) {
        return ApiResponse.<ContractTemplateResponse>builder()
                .result(templateService.getTemplateForLandlord(requireUserId(), templateId))
                .build();
    }

    @PatchMapping("/templates/{templateId}")
    public ApiResponse<ContractTemplateResponse> updateMyTemplate(
            @PathVariable String templateId,
            @Valid @RequestBody UpdateContractTemplateRequest request
    ) {
        return ApiResponse.<ContractTemplateResponse>builder()
                .message("Cập nhật mẫu hợp đồng thành công")
                .result(templateService.updateMyTemplate(requireUserId(), templateId, request))
                .build();
    }

    @PostMapping("/templates/{templateId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveMyTemplate(@PathVariable String templateId) {
        templateService.archiveMyTemplate(requireUserId(), templateId);
    }

    @GetMapping("/templates/{templateId}/versions")
    public ApiResponse<List<ContractTemplateVersionResponse>> getVersions(@PathVariable String templateId) {
        return ApiResponse.<List<ContractTemplateVersionResponse>>builder()
                .result(templateService.getVersionsForLandlord(requireUserId(), templateId))
                .build();
    }

    @PostMapping("/templates/{templateId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractTemplateVersionResponse> createMyVersion(
            @PathVariable String templateId,
            @Valid @RequestBody CreateTemplateVersionRequest request
    ) {
        return ApiResponse.<ContractTemplateVersionResponse>builder()
                .message("Tải lên phiên bản mẫu hợp đồng mới thành công")
                .result(templateService.createMyVersion(requireUserId(), templateId, request))
                .build();
    }

    @PostMapping("/templates/{templateId}/versions/{versionId}/publish")
    public ApiResponse<ContractTemplateVersionResponse> publishMyVersion(
            @PathVariable String templateId,
            @PathVariable String versionId
    ) {
        return ApiResponse.<ContractTemplateVersionResponse>builder()
                .message("Xuất bản phiên bản mẫu hợp đồng thành công")
                .result(templateService.publishMyVersion(requireUserId(), templateId, versionId))
                .build();
    }

    @PostMapping("/templates/{templateId}/versions/{versionId}/test-preview")
    public ResponseEntity<byte[]> testPreviewVersion(
            @PathVariable String templateId,
            @PathVariable String versionId
    ) {
        byte[] docBytes = templateService.testPreviewForLandlord(requireUserId(), templateId, versionId);

        boolean isPdf = docBytes.length >= 4
                && docBytes[0] == '%' && docBytes[1] == 'P' && docBytes[2] == 'D' && docBytes[3] == 'F';

        HttpHeaders headers = new HttpHeaders();
        if (isPdf) {
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("inline", "test_preview.pdf");
        } else {
            headers.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.setContentDispositionFormData("attachment", "test_preview.docx");
        }
        return new ResponseEntity<>(docBytes, headers, HttpStatus.OK);
    }

    // =========================================================================
    // HỢP ĐỒNG ĐÃ KÝ / BẢN NHÁP
    // =========================================================================

    @GetMapping("/applicable-templates")
    public ApiResponse<List<ContractTemplateResponse>> getApplicableTemplates(
            @RequestParam String rentalRequestId
    ) {
        return ApiResponse.<List<ContractTemplateResponse>>builder()
                .result(templateService.getApplicableTemplates(rentalRequestId))
                .build();
    }

    @GetMapping("/by-rental-request/{rentalRequestId}")
    public ApiResponse<ContractResponse> getByRentalRequest(@PathVariable String rentalRequestId) {
        return ApiResponse.<ContractResponse>builder()
                .result(contractService.findByRentalRequestId(rentalRequestId))
                .build();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractResponse> createDraft(
            @Valid @RequestBody CreateContractDraftRequest request
    ) {
        // Hợp đồng phải in được số CCCD của Bên A, mà CCCD chỉ có sau khi KYC thành công.
        kycGateService.requireVerifiedIdentity(requireUserContext().userId());
        return ApiResponse.<ContractResponse>builder()
                .message("Tạo bản nháp hợp đồng thành công")
                .result(contractService.createDraft(request))
                .build();
    }

    @GetMapping
    public PageResponse<ContractResponse> getMyContracts(
            @RequestParam(required = false) ContractStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return contractService.getContractsForCurrentUser(requireUserId(), status, page, size);
    }

    @GetMapping("/{contractId}")
    public ApiResponse<ContractResponse> getContract(@PathVariable String contractId) {
        return ApiResponse.<ContractResponse>builder()
                .result(contractService.getContract(contractId))
                .build();
    }

    @GetMapping("/{contractId}/revision")
    public ApiResponse<ContractRevisionResponse> getLatestRevision(@PathVariable String contractId) {
        return ApiResponse.<ContractRevisionResponse>builder()
                .result(contractService.getLatestRevision(contractId))
                .build();
    }

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

    @GetMapping("/{contractId}/completeness")
    public ApiResponse<ContractCompletenessResponse> getCompleteness(@PathVariable String contractId) {
        return ApiResponse.<ContractCompletenessResponse>builder()
                .result(contractService.getCompleteness(contractId))
                .build();
    }

    @PostMapping("/{contractId}/previews")
    public ApiResponse<ContractDocumentResponse> triggerPreview(@PathVariable String contractId) {
        return ApiResponse.<ContractDocumentResponse>builder()
                .message("Đã yêu cầu kết xuất tài liệu hợp đồng")
                .result(contractService.triggerPreview(contractId))
                .build();
    }

    @PostMapping("/{contractId}/send")
    public ApiResponse<ContractResponse> sendToTenant(@PathVariable String contractId) {
        kycGateService.requireVerifiedIdentity(requireUserContext().userId());
        return ApiResponse.<ContractResponse>builder()
                .message("Đã gửi hợp đồng cho người thuê")
                .result(contractService.sendToTenant(contractId))
                .build();
    }

    @GetMapping("/{contractId}/documents")
    public ApiResponse<List<ContractDocumentResponse>> getDocuments(@PathVariable String contractId) {
        return ApiResponse.<List<ContractDocumentResponse>>builder()
                .result(contractService.getDocumentsByContract(contractId))
                .build();
    }

    @GetMapping("/documents/{documentId}")
    public ApiResponse<ContractDocumentResponse> getDocument(@PathVariable String documentId) {
        return ApiResponse.<ContractDocumentResponse>builder()
                .result(contractService.getDocument(documentId))
                .build();
    }

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
