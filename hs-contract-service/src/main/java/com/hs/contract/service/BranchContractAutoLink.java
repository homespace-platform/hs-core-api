package com.hs.contract.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort first management link, fully isolated from contract creation: it is queued only after the commit, runs
 * in its own transaction and swallows its failures. On PostgreSQL a failed statement poisons the whole transaction, so
 * catching an error inside the business transaction could not save it. A lost link is repaired by attaching by hand.
 * It must not run on the creating thread: that thread keeps its connection until the after-commit callbacks return,
 * so asking for a second one there drains the pool under concurrent creations.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BranchContractAutoLink {

    private final BranchAssignmentService assignments;
    // One thread, so linking takes at most one connection.
    // shortcut: queued links are lost if the process dies; add a repair sweep if hand-attaching is not enough.
    private final ExecutorService links = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(200), task -> new Thread(task, "branch-auto-link"));

    /** First link of a newly created lease. */
    public void afterCommit(String contractId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    links.execute(() -> link(contractId));
                } catch (RejectedExecutionException full) {
                    log.warn("BRANCH_AUTO_LINK_REJECTED contract={}; queue full or shutting down", contractId, full);
                }
            }
        });
    }

    private void link(String contractId) {
        try {
            assignments.autoLink(contractId);
        } catch (RuntimeException failure) {
            log.warn("BRANCH_AUTO_LINK_FAILED contract={}", contractId, failure);
        }
    }

    /** Runs before the beans it depends on are destroyed, so queued links still have a database. */
    @PreDestroy
    void shutdown() {
        links.shutdown();
        try {
            if (!links.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("BRANCH_AUTO_LINK_SHUTDOWN dropped={}", links.shutdownNow().size());
            }
        } catch (InterruptedException interrupted) {
            links.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
