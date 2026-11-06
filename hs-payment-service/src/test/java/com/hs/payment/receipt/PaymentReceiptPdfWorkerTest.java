package com.hs.payment.receipt;

import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PaymentReceiptPdfWorkerTest {
  @Test void slowPdfDoesNotBlockTheSharedSchedulerOrStartOverlappingBatches() throws Exception {
    var repo=mock(PaymentReceiptRepository.class);var service=mock(PaymentReceiptService.class);
    var renderer=mock(PaymentReceiptPdfRenderer.class);var transactions=mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenAnswer(c->new SimpleTransactionStatus());
    var r=new PaymentReceipt();r.setId("r");r.setSnapshotHash("hash");r.setNextAttemptAt(Instant.now().minusSeconds(1));
    when(repo.pending(any(),any(),any())).thenReturn(List.of("r"));when(repo.lock("r")).thenReturn(Optional.of(r));
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var saved=new CountDownLatch(1);
    when(renderer.render(any(),eq("hash"))).thenAnswer(c->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));throw new IllegalStateException("converter test failure");});
    when(repo.save(any())).thenAnswer(c->{saved.countDown();return c.getArgument(0);});
    var worker=new PaymentReceiptPdfWorker(repo,service,renderer,mock(StorageService.class),mock(StorageObjectRepository.class),transactions);
    try{
      long start=System.nanoTime();worker.tick();assertTrue(entered.await(3,TimeUnit.SECONDS));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<2000,"tick returns while conversion is still blocked");
      worker.tick();verify(repo,times(1)).pending(any(),any(),any());release.countDown();assertTrue(saved.await(3,TimeUnit.SECONDS));
    }finally{release.countDown();worker.close();}
  }
}
