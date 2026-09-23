package com.hs.contract.scheduler;

import com.hs.contract.model.SignatureRequest;
import com.hs.contract.repository.SignatureRequestRepository;
import com.hs.contract.service.SmartCaSignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler nhúng chữ ký VNPT vào PDF cho các request PROVIDER_SIGNED.
 *
 * <p>Dùng atomic claim (claimForEmbedding) để đảm bảo chỉ một worker xử lý
 * mỗi request tại một thời điểm, ngay cả khi có nhiều instance đang chạy.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "homespace.smartca.enabled", havingValue = "true", matchIfMissing = false)
public class SignatureEmbeddingScheduler {

    private final SignatureRequestRepository requestRepository;
    private final SmartCaSignatureService signatureService;

    private static final int MAX_ATTEMPTS = 3;

    @Scheduled(fixedDelayString = "${homespace.smartca.embedding-delay-ms:5000}")
    public void embedPendingSignatures() {
        var staleBefore = java.time.Instant.now().minus(java.time.Duration.ofMinutes(2));
        requestRepository.recoverStaleEmbedding(staleBefore, MAX_ATTEMPTS);
        requestRepository.failStaleEmbedding(staleBefore, MAX_ATTEMPTS);
        List<SignatureRequest> pending = requestRepository.findPendingEmbeddingRequests(MAX_ATTEMPTS);
        if (pending.isEmpty()) return;

        log.info("SignatureEmbeddingScheduler: found {} requests to embed", pending.size());
        for (SignatureRequest req : pending) {
            try {
                signatureService.embedProviderSignature(req.getId());
            } catch (Exception e) {
                log.error("SignatureEmbeddingScheduler: error embedding request {}: {}", req.getId(), e.getMessage());
            }
        }
    }
}
