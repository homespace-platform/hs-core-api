package com.hs.contract.blockchain.worker;

import com.hs.contract.blockchain.config.BlockchainWorkerExecutorConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Timeout(5)
class BlockchainProofSchedulerTest {
    @Test void dispatcherSubmitsWorkWithoutRunningItOnSchedulerThread() {
        var worker = mock(ContractDocumentProofWorker.class);
        var executor = mock(TaskExecutor.class);
        new BlockchainProofScheduler(worker, executor).dispatch();
        verifyNoInteractions(worker);
        var captured = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captured.capture());
        captured.getValue().run();
        verify(worker).tick();
    }

    @Test void rejectionOfAnOverlappingWakeupIsSafe() {
        var worker = mock(ContractDocumentProofWorker.class);
        var executor = mock(TaskExecutor.class);
        doThrow(new TaskRejectedException("busy")).when(executor).execute(any());
        assertDoesNotThrow(() -> new BlockchainProofScheduler(worker, executor).dispatch());
        verifyNoInteractions(worker);
    }

    @Test void configuredPoolRunsAsynchronouslyAndDoesNotQueueAnotherTick() throws Exception {
        var worker = mock(ContractDocumentProofWorker.class);
        var executor = new BlockchainWorkerExecutorConfig().blockchainProofExecutor();
        executor.initialize();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var workerThread = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            workerThread.set(Thread.currentThread());
            started.countDown();
            release.await(3, TimeUnit.SECONDS);
            return null;
        }).when(worker).tick();
        try {
            var scheduler = new BlockchainProofScheduler(worker, executor);
            scheduler.dispatch();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), workerThread.get());
            assertTrue(workerThread.get().getName().startsWith("blockchain-proof-"));
            assertDoesNotThrow(scheduler::dispatch);
            assertEquals(0, executor.getThreadPoolExecutor().getQueue().size());
            verify(worker, times(1)).tick();
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
