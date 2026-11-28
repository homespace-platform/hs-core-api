package com.hs.api.controller.payment;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.service.PaymentProofUploadSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/u/payment-proof/session", produces = MediaType.APPLICATION_JSON_VALUE)
public class PaymentProofHandoffController {
    private static final String TOKEN_HEADER = "X-Proof-Upload-Token";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final PaymentProofUploadSessionService uploads;

    @GetMapping
    public ResponseEntity<SessionView> status(@RequestHeader(value = TOKEN_HEADER, required = false) String token) {
        requireToken(token);
        var session = uploads.findValidSessionByRawToken(token);
        if (session == null) throw new AppException(PaymentErrorCode.PROOF_SESSION_NOT_FOUND);

        String state = session.getStatus() == UploadSessionStatus.CONSUMED ? "UPLOADED" : "READY";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionView(state));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SessionView> upload(
            @RequestHeader(value = TOKEN_HEADER, required = false) String token,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        requireToken(token);

        uploads.handleMobileUpload(
                token, file.getBytes(), file.getOriginalFilename(), file.getContentType(), file.getSize());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SessionView("UPLOADED"));
    }

    private static void requireToken(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new AppException(PaymentErrorCode.PROOF_SESSION_NOT_FOUND);
        }
    }

    public record SessionView(String state) {}
}
