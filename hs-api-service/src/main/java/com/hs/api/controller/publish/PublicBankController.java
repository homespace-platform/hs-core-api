package com.hs.api.controller.publish;

import com.hs.common.dto.ApiResponse;
import com.hs.payment.dto.BankResponse;
import com.hs.payment.service.qr.VietQrBankService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/public/banks")
@RequiredArgsConstructor
public class PublicBankController {

    private final VietQrBankService bankService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<BankResponse>>> list() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(ApiResponse.<List<BankResponse>>builder().result(bankService.getBanks()).build());
    }
}
