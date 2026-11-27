package com.hs.contract.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class BranchContractAutoLinkTest {
    private final BranchAssignmentService assignments = mock(BranchAssignmentService.class);
    private final BranchContractAutoLink link = new BranchContractAutoLink(assignments);

    /** Spring still holds the creating transaction's connection while afterCommit callbacks run. */
    @Test
    void tenConcurrentCreationsOnATenConnectionPoolNeverStall() throws Exception {
        var pool = new Semaphore(10, true);
        var stalled = new AtomicInteger();
        var attempted = new CountDownLatch(10);
        doAnswer(call -> {
            if (pool.tryAcquire(2, TimeUnit.SECONDS)) pool.release();
            else stalled.incrementAndGet();
            attempted.countDown();
            return null;
        }).when(assignments).autoLink(anyString());
        var allHolding = new CyclicBarrier(10);
        try (var requests = Executors.newFixedThreadPool(10)) {
            List<Future<?>> creations = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String id = "contract-" + i;
                creations.add(requests.submit(() -> {
                    pool.acquire();
                    try {
                        allHolding.await(5, TimeUnit.SECONDS);
                        commit(id);
                    } finally {
                        pool.release();
                    }
                    return null;
                }));
            }
            for (var creation : creations) creation.get(30, TimeUnit.SECONDS);
        }
        assertTrue(attempted.await(10, TimeUnit.SECONDS), "every creation is linked");
        assertEquals(0, stalled.get(), "auto link waited for a connection its own creator was holding");
    }

    @Test
    void aFailingOrRejectedLinkNeverReachesTheCreator() {
        doThrow(new IllegalStateException("link failed")).when(assignments).autoLink("failing");
        assertDoesNotThrow(() -> commit("failing"));
        verify(assignments, timeout(5000)).autoLink("failing");

        link.shutdown();
        assertDoesNotThrow(() -> commit("after-shutdown"));
        verify(assignments, never()).autoLink("after-shutdown");
    }

    private void commit(String contractId) {
        TransactionSynchronizationManager.initSynchronization();
        try {
            link.afterCommit(contractId);
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
