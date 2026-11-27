package com.hs.payment.membership;

import com.hs.common.advice.entity.AppException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MembershipOverpaymentAdminServiceTest {
    final MembershipOverpaymentRepository repo = mock(MembershipOverpaymentRepository.class);
    final MembershipOverpaymentAdminService service = new MembershipOverpaymentAdminService(repo);

    MembershipOverpayment row() {
        var p = new MembershipOverpayment();
        p.setId("p1"); p.setOrderId("o1"); p.setOwnerId("u"); p.setProvider(MembershipOrder.Provider.SEPAY);
        p.setTransactionNo("tx"); p.setAmountVnd(49_000); p.setReceivedAt(Instant.now());
        when(repo.lockById("p1")).thenReturn(Optional.of(p));
        return p;
    }

    @Test void markRefundedRecordsActorOnceAndIsIdempotent() {
        var p = row();
        var v = service.markRefunded("p1", "admin-1", " da hoan ");
        assertEquals("REFUNDED", v.status());
        assertEquals("admin-1", p.getRefundedBy());
        assertEquals("da hoan", p.getNote());
        var at = p.getRefundedAt();
        service.markRefunded("p1", "admin-2", "again");
        assertEquals("admin-1", p.getRefundedBy());
        assertEquals(at, p.getRefundedAt());
        verify(repo, times(1)).save(p);
    }

    @Test void unknownIdIsNotFound() {
        when(repo.lockById("x")).thenReturn(Optional.empty());
        assertThrows(AppException.class, () -> service.markRefunded("x", "a", null));
    }
}
