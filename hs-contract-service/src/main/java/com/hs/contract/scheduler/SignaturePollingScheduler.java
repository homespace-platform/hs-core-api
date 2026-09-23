package com.hs.contract.scheduler;

import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.contract.repository.SignatureRequestRepository;
import com.hs.contract.service.SmartCaSignatureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Scheduler poll trạng thái từ VNPT cho các request PENDING_USER_CONFIRMATION.
 *
 * <p>Chỉ chạy khi {@code homespace.smartca.enabled=true}.
 * Khi VNPT trả SIGNED → service chuyển sang PROVIDER_SIGNED để embedding worker xử lý.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableScheduling
@ConditionalOnProperty(name = "homespace.smartca.enabled", havingValue = "true", matchIfMissing = false)
public class SignaturePollingScheduler {

    private final SignatureRequestRepository requestRepository;
    private final SmartCaSignatureService signatureService;

    @Scheduled(fixedDelayString = "${homespace.smartca.poll-interval:5000}")
    public void pollPendingSignatures() {
        List<SignatureRequest> pending = new java.util.ArrayList<>(
                requestRepository.findByStatus(SignatureRequestStatus.PENDING_USER_CONFIRMATION));
        pending.addAll(requestRepository.findByStatus(SignatureRequestStatus.CREATED));
        if (pending.isEmpty()) return;

        log.debug("SignaturePollingScheduler: polling {} pending requests", pending.size());
        for (SignatureRequest req : pending) {
            try {
                signatureService.processPendingRequest(req.getId());
            } catch (Exception e) {
                log.warn("SignaturePollingScheduler: error polling request {}: {}", req.getId(), e.getMessage());
            }
        }
    }
}
