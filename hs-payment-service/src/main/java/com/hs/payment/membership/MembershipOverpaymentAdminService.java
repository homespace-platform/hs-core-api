package com.hs.payment.membership;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class MembershipOverpaymentAdminService {
    private static final Logger log = LoggerFactory.getLogger(MembershipOverpaymentAdminService.class);
    private final MembershipOverpaymentRepository repo;

    public record View(String id, String orderId, String ownerId, String provider, String transactionNo, int amountVnd,
                       Instant receivedAt, String status, Instant refundedAt, String refundedBy, String note) {
        static View of(MembershipOverpayment p) {
            return new View(p.getId(), p.getOrderId(), p.getOwnerId(), p.getProvider().name(), p.getTransactionNo(),
                    p.getAmountVnd(), p.getReceivedAt(), p.getStatus().name(), p.getRefundedAt(), p.getRefundedBy(), p.getNote());
        }
    }

    @Transactional(readOnly = true)
    public Page<View> list(MembershipOverpayment.Status status, int page, int size) {
        return repo.findByStatus(status, PageRequest.of(Math.max(0, page - 1), Math.min(100, Math.max(1, size)),
                Sort.by("receivedAt", "id"))).map(View::of);
    }

    /** Idempotent: marking an already refunded row returns it unchanged. */
    @Transactional
    public View markRefunded(String id, String adminId, String note) {
        var p = repo.lockById(id).orElseThrow(() ->
                new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND, "Không tìm thấy khoản trả thừa."));
        if (p.getStatus() == MembershipOverpayment.Status.REFUND_PENDING) {
            p.setStatus(MembershipOverpayment.Status.REFUNDED);
            p.setRefundedAt(Instant.now());
            p.setRefundedBy(adminId);
            p.setNote(note == null || note.isBlank() ? null : note.strip());
            repo.save(p);
            log.info("Membership overpayment {} marked refunded by admin {}", p.getId(), adminId); // no bank data
        }
        return View.of(p);
    }
}
