package com.hs.api.controller.internal;

import java.util.Base64;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.document.InternalDocumentError;
import com.hs.common.document.InternalDocumentFile;
import com.hs.common.dto.ApiResponse;
import com.hs.contract.service.LeaseBundleDocumentService;
import com.hs.payment.receipt.MembershipReceiptService;
import com.hs.payment.receipt.PaymentReceiptService;

import lombok.RequiredArgsConstructor;

/**
 * Notification-service only: PDFs to attach to emails. /internal/** is rejected without the gateway secret by
 * UserContextFilter. Ids: rental-receipts = payment request id (event data.paymentId; a receipt id also works),
 * membership-receipts = membership order id (data.orderId), overpayment-receipts = overpayment id,
 * lease-bundles = lease contract id. recipientId must be the receipt owner / a contract party.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/documents")
public class DocumentInternalController {
    static final long MAX_TOTAL_BYTES = 35L * 1024 * 1024;

    private final PaymentReceiptService rentalReceipts;
    private final MembershipReceiptService membershipReceipts;
    private final LeaseBundleDocumentService leaseBundles;

    public record FileDto(String fileName, String contentType, String contentBase64, long sizeBytes) { }

    public record DocumentsResponse(List<FileDto> files) { }

    @GetMapping("/{kind}/{id}")
    public ResponseEntity<ApiResponse<DocumentsResponse>> get(@PathVariable String kind, @PathVariable String id,
            @RequestParam(required = false) String recipientId) {
        if (recipientId == null || recipientId.isBlank()) throw new AppException(ErrorCode.INVALID_REQUEST);
        List<InternalDocumentFile> files = switch (kind) {
            case "rental-receipts" -> List.of(rentalReceipts.pdfForRecipient(id, recipientId));
            case "membership-receipts" -> List.of(membershipReceipts.pdfForRecipient(id, recipientId, false));
            case "overpayment-receipts" -> List.of(membershipReceipts.pdfForRecipient(id, recipientId, true));
            case "lease-bundles" -> leaseBundles.bundle(id, recipientId);
            default -> throw InternalDocumentError.NOT_FOUND.exception();
        };
        // Over the limit the caller sends a link instead of attachments.
        if (files.stream().mapToLong(f -> f.bytes().length).sum() > MAX_TOTAL_BYTES) throw InternalDocumentError.TOO_LARGE.exception();
        var body = files.stream().map(f -> new FileDto(f.fileName(), "application/pdf",
                Base64.getEncoder().encodeToString(f.bytes()), f.bytes().length)).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.<DocumentsResponse>builder().result(new DocumentsResponse(body)).build());
    }
}
