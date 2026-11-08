package com.hs.contract.blockchain.worker;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "homespace.blockchain.enabled", havingValue = "true")
public class BlockchainProofScheduler {
    private final ContractDocumentProofWorker worker;
    private final TaskExecutor executor;

    public BlockchainProofScheduler(ContractDocumentProofWorker worker,
            @Qualifier("blockchainProofExecutor") TaskExecutor executor) {
        this.worker = worker;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${homespace.blockchain.poll-delay-ms:10000}")
    public void dispatch() {
        try {
            // RPC chạy ở pool riêng để không giữ luồng scheduler đang poll/nhúng chữ ký SmartCA.
            executor.execute(worker::tick);
        } catch (TaskRejectedException busy) {
            // Lượt trước chưa xong: bỏ lượt đánh thức này, không tích lũy hàng đợi trong bộ nhớ.
        }
    }
}
