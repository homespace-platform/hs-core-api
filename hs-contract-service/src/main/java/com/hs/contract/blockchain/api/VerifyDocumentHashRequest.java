package com.hs.contract.blockchain.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyDocumentHashRequest(
        @NotBlank @Pattern(regexp = "^0x[0-9a-fA-F]{64}$", message = "Hash phải là SHA-256 dạng 0x và 64 ký tự hex")
        String documentHash) {
}
