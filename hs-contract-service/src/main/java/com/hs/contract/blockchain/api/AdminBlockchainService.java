package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.CurrentUser;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.payment.membership.AdminEthPaymentQueries;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.*;
import java.util.*;

import static com.hs.contract.blockchain.api.AdminBlockchainResponse.*;

@Service
@RequiredArgsConstructor
public class AdminBlockchainService {
    private final AdminBlockchainQueries reads;
    private final AdminEthPaymentQueries payments;
    private final BlockchainProperties config;
    private final RegistryClient registry;
    private final ContractDocumentIntegrityService integrity;
    private final Map<String, List<Instant>> checks = new HashMap<>();

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Summary summary() {
        CurrentUser.requireAdminWith("BLOCKCHAIN_VIEW");
        return summary(Instant.now());
    }
    Summary summary(Instant now) {
        var queue = reads.queue(now);
        var eth = payments.counts();
        return new Summary(now, new Network(config.getChainId(), config.getChainId() == 11155111 ? "Sepolia" : "UNKNOWN",
                AdminBlockchainQueries.address(config.getRegistryAddress()), config.isEnabled(),
                config.getChainId() == 11155111 ? "https://sepolia.etherscan.io" : null), queue.counts(), queue.stuck(),
                queue.oldest() == null ? null : Math.max(0, Duration.between(queue.oldest(), now).getSeconds()), queue.retrying(),
                queue.errors(), reads.openIncidents(), reads.senderLease(), new Integrity(reads.integrityCounts(),
                "Latest explicit check only; results may be stale. UNCHECKED includes legacy documents."), eth.unsettled(), eth.total(), eth.paid(),
                null, Map.of("workerLastRun", "UNAVAILABLE_NO_PERSISTED_JOB_RUNS", "integrity", "LATEST_EXPLICIT_CHECK_ONLY",
                        "ethChain", "ASSUMED_SEPOLIA_NO_STORED_CHAIN_ID"));
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ProofRow> proofs(String status, String prefix, Instant from, Instant to, Pageable page) {
        CurrentUser.requireAdminWith("BLOCKCHAIN_VIEW");
        return reads.proofs(status, prefix, from, to, page);
    }
    public ProofDetail proof(String id) {
        CurrentUser.requireAdminWith("BLOCKCHAIN_VIEW");
        return reads.proof(id).orElseThrow(() -> new AppException(ContractErrorCode.CONTRACT_DOCUMENT_NOT_FOUND));
    }
    public Page<AdminEthPaymentQueries.Payment> payments(boolean unsettled, Pageable page) {
        CurrentUser.requireAdminWith("BLOCKCHAIN_VIEW");
        return payments.payments(unsettled, page, Instant.now());
    }
    public Verification verify(String id) {
        var proof = proof(id);
        limit(CurrentUser.requireUserId(), Instant.now());
        if (proof.documentHash() == null || proof.onChainDocumentId() == null || proof.registryAddress() == null
                || !BlockchainProperties.supportedChain(proof.chainId())) return unavailable(id);
        try {
            var chain = registry.readDocument(proof.chainId(), proof.registryAddress(), proof.onChainDocumentId());
            if (chain == null || AdminBlockchainQueries.hash(chain.documentHash()) == null) return unavailable(id);
            boolean registered = chain.exists();
            boolean matches = registered && chain.documentHash().equalsIgnoreCase(proof.documentHash());
            return new Verification(id, "AVAILABLE", registered, matches, registered && !matches, chain.documentHash(), chain.registeredAt());
        } catch (RuntimeException unavailable) {
            return unavailable(id);
        }
    }
    public DocumentIntegrityResponse integrity(String id) {
        CurrentUser.requireAdminWith("BLOCKCHAIN_VIEW");
        CurrentUser.requireAdminWith("CONTRACT_VIEW");
        proof(id);
        limit(CurrentUser.requireUserId(), Instant.now());
        return integrity.checkForAdmin(id);
    }
    private Verification unavailable(String id) { return new Verification(id, "UNAVAILABLE", null, null, null, null, null); }

    // shortcut: per-instance quota; use a shared limiter if the admin API is deployed across multiple replicas.
    synchronized void limit(String user, Instant now) {
        checks.values().forEach(times -> times.removeIf(t -> !t.isAfter(now.minusSeconds(60))));
        checks.values().removeIf(List::isEmpty);
        var times = checks.get(user);
        if (times != null && times.size() >= 10 || times == null && checks.size() >= 10000)
            throw new AppException(4290, "Too many blockchain checks; retry in one minute.", HttpStatus.TOO_MANY_REQUESTS);
        checks.computeIfAbsent(user, k -> new ArrayList<>()).add(now);
    }
}
