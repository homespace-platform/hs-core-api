package com.hs.contract.blockchain.api;

import java.time.Instant;

/** Kết quả kiểm tra file hiện tại; độc lập với trạng thái ghi nhận transaction. */
public record DocumentIntegrityResponse(String documentId, String status, String documentHash,
        String onChainDocumentHash, Instant checkedAt, String message) { }
