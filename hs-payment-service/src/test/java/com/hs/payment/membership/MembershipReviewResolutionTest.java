package com.hs.payment.membership;

import com.hs.listing.membership.MembershipEventRepository;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.config.MembershipReviewResolutionJob;
import com.hs.payment.membership.notification.MembershipNotificationWriter;
import com.hs.payment.receipt.MembershipReceiptService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MembershipReviewResolutionTest {
    final MembershipOrderRepository orders = mock(MembershipOrderRepository.class);
    final MembershipEventRepository events = mock(MembershipEventRepository.class);
    final MembershipService memberships = mock(MembershipService.class);
    final MembershipReceiptService receipts = mock(MembershipReceiptService.class);
    final MembershipOverpaymentRepository overpayments = mock(MembershipOverpaymentRepository.class);
    final MembershipNotificationWriter notifications = mock(MembershipNotificationWriter.class);
    final MembershipPaymentApplier applier = new MembershipPaymentApplier(orders, overpayments, memberships,
            notifications, receipts);
    final TransactionTemplate tx = new TransactionTemplate() {
        @Override public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> a) { return a.doInTransaction(null); }
    };
    final MembershipReviewResolutionJob job = new MembershipReviewResolutionJob(orders, events, memberships, applier, tx);

    MembershipOrder review(String id, String txNo) {
        var o = new MembershipOrder();
        o.setId(id); o.setOwnerId("owner"); o.setTier(MembershipTier.STARTER); o.setAmountVnd(49_000);
        o.setProvider(MembershipOrder.Provider.SEPAY); o.setStatus(MembershipOrder.Status.REVIEW); o.setTransactionNo(txNo);
        when(orders.findById(id)).thenReturn(Optional.of(o));
        when(orders.lockById(id)).thenReturn(Optional.of(o));
        return o;
    }

    void queue(MembershipOrder... rows) {
        // Real repository would stop returning them once they are no longer REVIEW.
        when(orders.findByStatusOrderById(eq(MembershipOrder.Status.REVIEW), any())).thenAnswer(c ->
                new SliceImpl<>(Stream.of(rows).filter(r -> r.getStatus() == MembershipOrder.Status.REVIEW).toList()));
    }

    @Test
    void neverActivatedReviewRowIsActivatedAsLatePaymentAndRerunDoesNothing() {
        var o = review("a1", "tx-late");
        queue(o);
        when(events.existsByOrderId("a1")).thenReturn(false);
        when(memberships.activate("owner", MembershipTier.STARTER, false, "a1")).thenReturn(true);
        assertEquals(1, job.run(10));
        assertEquals(MembershipOrder.Status.PAID, o.getStatus());
        verify(receipts).capture(o);
        verifyNoInteractions(notifications); // legacy resolution must not trigger emails
        assertEquals(0, job.run(10));
        verify(memberships, times(1)).activate(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    void secondPaymentReviewRowGoesBackToPaidAndBecomesOneOverpayment() {
        var o = review("b1", "tx-first");
        queue(o);
        when(events.existsByOrderId("b1")).thenReturn(true);
        assertEquals(1, job.run(10));
        assertEquals(MembershipOrder.Status.PAID, o.getStatus());
        var saved = ArgumentCaptor.forClass(MembershipOverpayment.class);
        verify(overpayments).save(saved.capture());
        assertEquals("rv-b1", saved.getValue().getTransactionNo());
        verifyNoInteractions(notifications);
        assertEquals(0, job.run(10));
        verify(memberships, never()).activate(anyString(), any(), anyBoolean(), anyString());
        verify(overpayments, times(1)).save(any());
    }

    @Test
    void unactivatableReviewRowFailsAndIsRefundable() {
        var o = review("c1", "tx-c");
        queue(o);
        job.run(10);
        assertEquals(MembershipOrder.Status.FAILED, o.getStatus());
        verify(overpayments).save(any());
    }

    @Test
    void noMembershipCodeAssignsReviewStatus() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').matches(".*/(membership|service|config|receipt)/.*"))
                    .filter(p -> { try { return Files.readString(p).contains("MembershipOrder.Status.REVIEW"); }
                        catch (IOException e) { throw new RuntimeException(e); } })
                    .toList();
            // Only the legacy resolver may name REVIEW, and only to read/compare it.
            assertEquals(List.of("MembershipPaymentApplier.java", "MembershipReviewResolutionJob.java"),
                    offenders.stream().map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertFalse(Files.readString(root.resolve("com/hs/payment/membership/MembershipPaymentApplier.java")).contains("setStatus(MembershipOrder.Status.REVIEW)"));
    }
}
