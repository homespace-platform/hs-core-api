package com.hs.contract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Best-effort first management link, fully isolated from contract creation: it runs only after the commit, in its
 * own transaction, and swallows its failures. On PostgreSQL a failed statement poisons the whole transaction, so
 * catching an error inside the business transaction could not save it. A lost link is repaired by attaching by hand.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BranchContractAutoLink {

    private final BranchAssignmentService assignments;

    /** First link of a newly created lease. */
    public void afterCommit(String contractId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    assignments.autoLink(contractId);
                } catch (RuntimeException failure) {
                    log.warn("BRANCH_AUTO_LINK_FAILED contract={}", contractId, failure);
                }
            }
        });
    }
}
