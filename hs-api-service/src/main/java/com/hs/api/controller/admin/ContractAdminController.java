package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.common.dto.PageResponse;
import com.hs.contract.dto.catalog.TemplateFieldDefinition;
import com.hs.contract.dto.request.CreateContractTemplateRequest;
import com.hs.contract.dto.request.CreateTemplateVersionRequest;
import com.hs.contract.dto.request.UpdateContractTemplateRequest;
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
import com.hs.listing.model.constant.RentalMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping({"/admin", "/api/v1/admin"})
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAuthority('ADMIN')")
public class ContractAdminController {

    private final ContractTemplateService templateService;
    private final ContractService contractService;

    // =========================================================================
    // QUẢN LÝ MẪU HỢP ĐỒNG (ADMIN)
    // =========================================================================

    /**
     * Lấy danh mục mã trường (placeholder catalog) được hệ thống hỗ trợ
     */
    @GetMapping("/contract-template-fields")
    public ApiResponse<List<TemplateFieldDefinition>> getCatalogFields() {
        return ApiResponse.<List<TemplateFieldDefinition>>builder()
                .result(templateService.getCatalogFields())
                .build();
    }

    /**
     * Danh sách các mẫu hợp đồng (phân trang PageResponse)
     */
    @GetMapping("/contract-templates")
    public PageResponse<ContractTemplateResponse> listTemplates(
            @RequestParam(required = false) ContractTemplateStatus status,
            @RequestParam(required = false) ListingCategory category,
            @RequestParam(required = false) RentalMode rentalMode,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return templateService.listTemplates(status, category, rentalMode, page, size);
    }

    /**
     * Tạo mới mẫu hợp đồng kèm phiên bản Word đầu tiên
     */
    @PostMapping("/contract-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractTemplateResponse> createTemplate(
            @Valid @RequestBody CreateContractTemplateRequest request
    ) {
        return ApiResponse.<ContractTemplateResponse>builder()
                .message("Tạo mẫu hợp đồng thành công")
                .result(templateService.createTemplate(request))
                .build();
    }

    /**
     * Chi tiết mẫu hợp đồng
     */
    @GetMapping("/contract-templates/{templateId}")
    public ApiResponse<ContractTemplateResponse> getTemplate(@PathVariable String templateId) {
        return ApiResponse.<ContractTemplateResponse>builder()
                .result(templateService.getTemplate(templateId))
                .build();
    }

    /**
     * Cập nhật thông tin mẫu (tên, mô tả, phạm vi)
     */
    @PatchMapping("/contract-templates/{templateId}")
    public ApiResponse<ContractTemplateResponse> updateTemplate(
            @PathVariable String templateId,
            @Valid @RequestBody UpdateContractTemplateRequest request
    ) {
        return ApiResponse.<ContractTemplateResponse>builder()
                .message("Cập nhật thông tin mẫu hợp đồng thành công")
                .result(templateService.updateTemplate(templateId, request))
                .build();
    }

    /**
     * Thêm phiên bản Word mới cho mẫu
     */
    @PostMapping("/contract-templates/{templateId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ContractTemplateVersionResponse> createVersion(
            @PathVariable String templateId,
            @Valid @RequestBody CreateTemplateVersionRequest request
    ) {
        return ApiResponse.<ContractTemplateVersionResponse>builder()
                .message("Tải lên phiên bản mẫu hợp đồng mới thành công")
                .result(templateService.createVersion(templateId, request))
                .build();
    }

    /**
     * Danh sách các phiên bản của một mẫu
     */
    @GetMapping("/contract-templates/{templateId}/versions")
    public ApiResponse<List<ContractTemplateVersionResponse>> getVersions(@PathVariable String templateId) {
        return ApiResponse.<List<ContractTemplateVersionResponse>>builder()
                .result(templateService.getVersions(templateId))
                .build();
    }

    /**
     * Chi tiết một phiên bản mẫu
     */
    @GetMapping("/contract-templates/{templateId}/versions/{versionId}")
    public ApiResponse<ContractTemplateVersionResponse> getVersion(
            @PathVariable String templateId,
            @PathVariable String versionId
    ) {
        return ApiResponse.<ContractTemplateVersionResponse>builder()
                .result(templateService.getVersion(templateId, versionId))
                .build();
    }

    /**
     * Xuất bản phiên bản Word (để người dùng có thể áp dụng)
     */
    @PostMapping("/contract-templates/{templateId}/versions/{versionId}/publish")
    public ApiResponse<ContractTemplateVersionResponse> publishVersion(
            @PathVariable String templateId,
            @PathVariable String versionId
    ) {
        return ApiResponse.<ContractTemplateVersionResponse>builder()
                .message("Xuất bản phiên bản mẫu hợp đồng thành công")
                .result(templateService.publishVersion(templateId, versionId))
                .build();
    }

    /**
     * Xem thử (Test Preview) mẫu Word với Dummy Data
     */
    @PostMapping("/contract-templates/{templateId}/versions/{versionId}/test-preview")
    public ResponseEntity<byte[]> testPreviewVersion(
            @PathVariable String templateId,
            @PathVariable String versionId
    ) {
        byte[] docBytes = templateService.testPreviewVersion(templateId, versionId);

        boolean isPdf = docBytes.length >= 4 && docBytes[0] == '%' && docBytes[1] == 'P' && docBytes[2] == 'D' && docBytes[3] == 'F';

        HttpHeaders headers = new HttpHeaders();
        if (isPdf) {
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("inline", "test_preview.pdf");
        } else {
            headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
            headers.setContentDispositionFormData("attachment", "test_preview.docx");
        }

        return new ResponseEntity<>(docBytes, headers, HttpStatus.OK);
    }

    /**
     * Lưu trữ (ngừng áp dụng) mẫu hợp đồng
     */
    @PostMapping("/contract-templates/{templateId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveTemplate(@PathVariable String templateId) {
        templateService.archiveTemplate(templateId);
    }

    // =========================================================================
    // QUẢN LÝ HỢP ĐỒNG HỆ THỐNG (ADMIN)
    // =========================================================================

    /**
     * Lấy toàn bộ danh sách hợp đồng hệ thống (phân trang PageResponse)
     */
    @GetMapping("/contracts")
    public PageResponse<ContractResponse> getAllContracts(
            @RequestParam(required = false) ContractStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return contractService.getAllContractsForAdmin(status, keyword, page, size);
    }

    /**
     * Chi tiết một hợp đồng
     */
    @GetMapping("/contracts/{contractId}")
    public ApiResponse<ContractResponse> getContract(@PathVariable String contractId) {
        return ApiResponse.<ContractResponse>builder()
                .result(contractService.getContractForAdmin(contractId))
                .build();
    }

    /**
     * Lấy dữ liệu snapshot revision mới nhất của hợp đồng
     */
    @GetMapping("/contracts/{contractId}/revision")
    public ApiResponse<ContractRevisionResponse> getContractRevision(@PathVariable String contractId) {
        return ApiResponse.<ContractRevisionResponse>builder()
                .result(contractService.getLatestRevisionForAdmin(contractId))
                .build();
    }

    /**
     * Lấy danh sách tài liệu DOCX / PDF đã sinh của hợp đồng
     */
    @GetMapping("/contracts/{contractId}/documents")
    public ApiResponse<List<ContractDocumentResponse>> getContractDocuments(@PathVariable String contractId) {
        return ApiResponse.<List<ContractDocumentResponse>>builder()
                .result(contractService.getDocumentsForAdmin(contractId))
                .build();
    }
}
