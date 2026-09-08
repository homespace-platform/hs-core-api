package com.hs.api.controller.webhook;

import com.hs.common.dto.ApiResponse;
import com.hs.user.service.KycService;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public at Gateway JWT layer; authenticity is Didit HMAC ({@code X-Signature-V2}).
 * Gateway rewrites {@code /api/v1/webhooks/didit} → {@code /webhooks/didit}.
 */
@RestController
@RequestMapping("/webhooks/didit")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class DiditWebhookController {

    KycService kycService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> handle(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Signature-V2", required = false) String signatureV2,
            @RequestHeader(value = "X-Signature-Simple", required = false) String signatureSimple,
            @RequestHeader(value = "X-Timestamp", required = false) String timestamp
    ) {
        kycService.handleDiditWebhook(rawBody, signatureV2, signatureSimple, timestamp);
        return ApiResponse.<Void>builder()
                .message("Webhook accepted")
                .build();
    }
}
