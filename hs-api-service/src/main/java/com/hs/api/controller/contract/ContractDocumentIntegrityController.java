package com.hs.api.controller.contract;

import com.hs.common.dto.ApiResponse;
import com.hs.contract.blockchain.api.ContractDocumentIntegrityService;
import com.hs.contract.blockchain.api.DocumentIntegrityResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/contracts/documents/{documentId}", "/api/v1/contracts/documents/{documentId}"})
public class ContractDocumentIntegrityController {
    private final ContractDocumentIntegrityService integrity;

    @PostMapping("/integrity-check")
    public ResponseEntity<ApiResponse<DocumentIntegrityResponse>> check(@PathVariable String documentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.<DocumentIntegrityResponse>builder().result(integrity.check(documentId)).build());
    }

    @GetMapping("/content")
    public ResponseEntity<byte[]> content(@PathVariable String documentId,
            @RequestParam(defaultValue = "false") boolean download) {
        var pdf = integrity.content(documentId);
        String fileName = pdf.fileName() == null ? "hop-dong.pdf" : pdf.fileName().replaceAll("[\\r\\n]", "_");
        var disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Document-Integrity", pdf.integrity().status())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(pdf.bytes().length).body(pdf.bytes());
    }
}
