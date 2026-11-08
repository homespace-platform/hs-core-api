package com.hs.api.controller.contract;

import com.hs.common.dto.ApiResponse;
import com.hs.contract.blockchain.api.BlockchainProofResponse;
import com.hs.contract.blockchain.api.ContractDocumentProofQueryService;
import com.hs.contract.blockchain.api.VerifyDocumentHashRequest;
import com.hs.contract.blockchain.api.VerifyDocumentHashResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/contracts/documents/{documentId}/blockchain-proof", "/api/v1/contracts/documents/{documentId}/blockchain-proof"})
@RequiredArgsConstructor
public class ContractBlockchainProofController {
    private final ContractDocumentProofQueryService proofs;

    @GetMapping
    public ResponseEntity<ApiResponse<BlockchainProofResponse>> get(@PathVariable String documentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.<BlockchainProofResponse>builder().result(proofs.get(documentId)).build());
    }

    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<VerifyDocumentHashResponse>> verify(
            @PathVariable String documentId, @Valid @RequestBody VerifyDocumentHashRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.<VerifyDocumentHashResponse>builder()
                        .result(proofs.verify(documentId, request.documentHash())).build());
    }
}
