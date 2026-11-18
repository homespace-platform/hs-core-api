package com.hs.api.controller.admin;

import com.hs.contract.blockchain.api.ContractDocumentIntegrityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** Admin read of a verified signed PDF. The user route (/contracts/documents/...) is party-only. */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/admin/contracts/documents/{documentId}", "/api/v1/admin/contracts/documents/{documentId}"})
public class ContractDocumentAdminController {
    private final ContractDocumentIntegrityService integrity;

    @PreAuthorize("hasAuthority('ADMIN') and hasAuthority('CONTRACT_VIEW')")
    @RequestMapping(value = "/content", method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<byte[]> content(@PathVariable String documentId,
            @RequestParam(defaultValue = "false") boolean download, HttpServletRequest request) {
        var pdf = integrity.contentForAdmin(documentId);
        boolean appRequest = "POST".equals(request.getMethod());
        String fileName = pdf.fileName() == null ? "hop-dong.pdf" : pdf.fileName().replaceAll("[\r\n]", "_");
        var disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(fileName, StandardCharsets.UTF_8).build();
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(appRequest ? MediaType.parseMediaType("application/vnd.homespace.verified-pdf") : MediaType.APPLICATION_PDF);
        if (!appRequest) response.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        return response
                .header("X-Document-Integrity", pdf.integrity().status())
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(pdf.bytes().length).body(pdf.bytes());
    }
}
