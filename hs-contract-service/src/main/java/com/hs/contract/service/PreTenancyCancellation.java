package com.hs.contract.service;

import com.hs.contract.model.Contract;
import com.hs.contract.model.SignatureRequest;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.model.constant.SignatureRequestStatus;
import com.hs.payment.model.DepositRecord;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.DepositStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.service.PaymentRequestService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Single source of truth for pre-tenancy cancellation, shared by the mutual-agreement path
 * ({@link RentalOnboardingService}) and the resolution workflow ({@link PreTenancyResolutionService}).
 * Lease fully signed: blocked. Deposit agreement fully signed: cancellation allowed, deposit forfeited, first rent refunded.
 */
final class PreTenancyCancellation {
    static final String LEASE_SIGNED = "HĐ thuê đã đủ chữ ký; cần dùng chấm dứt hợp đồng.";
    static final String SIGNATURE_PENDING = "Chữ ký đang xử lý hoặc chưa rõ kết quả; cần đối soát nhà cung cấp trước.";
    static final String FORFEIT_REASON = "Hủy sau khi HĐ cọc đã đủ chữ ký; cọc không hoàn";
    static final String FORFEIT_NOTICE = "HĐ cọc đã đủ chữ ký: tiền cọc không được hoàn, chỉ hoàn tiền thuê tháng đầu nếu đã nhận.";
    private static final Set<ContractStatus> SIGNED = Set.of(ContractStatus.ACTIVE, ContractStatus.EXPIRED, ContractStatus.CLOSED, ContractStatus.TERMINATED);
    private static final Set<SignatureRequestStatus> DEAD = Set.of(SignatureRequestStatus.REJECTED, SignatureRequestStatus.CANCELLED, SignatureRequestStatus.EXPIRED);

    static final String DEPOSIT_REFUNDED_END = "Hai bên hủy trước khi thuê; tiền cọc đã được hoàn";

    private PreTenancyCancellation() {}

    /**
     * Ends the deposit agreement when a cancellation completes. Partial signatures: CANCELLED (returns true, caller saves).
     * Fully signed: TERMINATED through the agreement service, which keeps the signed PDF and both signatures untouched.
     */
    static boolean endDepositAgreement(com.hs.contract.deposit.DepositAgreementService agreements, Contract dc, DepositRecord d, String rentalId) {
        if (dc == null) return false;
        if (!fullySigned(dc)) { dc.setStatus(ContractStatus.CANCELLED); return true; }
        if (agreements != null && dc.getStatus() == ContractStatus.ACTIVE)
            agreements.terminateForCancellation(rentalId, d != null && d.getStatus() == DepositStatus.RETAINED_BY_LANDLORD ? FORFEIT_REASON : DEPOSIT_REFUNDED_END);
        return false;
    }

    /** Both parties signed. Zero or one signature is "partially signed". */
    static boolean fullySigned(Contract c) {
        return c != null && (c.getSignedAt() != null || SIGNED.contains(c.getStatus()));
    }

    /** A request that is neither finished nor safely dead: cancelling mid-signing is unsafe. */
    static boolean signatureInProgress(Collection<SignatureRequest> rows) {
        return rows.stream().anyMatch(r -> r.getStatus() != SignatureRequestStatus.SIGNED
            && (r.getProviderSignatureValue() != null || !DEAD.contains(r.getStatus())));
    }

    /** The deposit is forfeited when its agreement is fully signed and a positive amount is actually held. */
    static boolean forfeits(Contract depositAgreement, DepositRecord d) {
        return fullySigned(depositAgreement) && d != null && d.getStatus() == DepositStatus.HELD
            && d.getHeldAmount() != null && d.getHeldAmount().signum() > 0;
    }

    /** Same retention fields as the tenant-default path. The caller saves the record. */
    static void retain(DepositRecord d, Instant now, String reason) {
        d.setRetainedAmount(d.getHeldAmount()); d.setHeldAmount(BigDecimal.ZERO);
        d.setRefundableAmount(BigDecimal.ZERO); d.setStatus(DepositStatus.RETAINED_BY_LANDLORD);
        d.setRetainedAt(now); d.setRetainedReason(reason);
    }

    /**
     * Creates the refund obligation for confirmed receipts (total > 0). When forfeiting, retains the deposit and
     * refunds only the rest (separate first-rent receipt, or the rent part of a combined receipt).
     * The caller saves {@code d}. Returns whether a refund is now owed.
     */
    static boolean refund(PaymentRequestService payments, List<PaymentRequest> receipts, DepositRecord d, boolean forfeit, Instant now) {
        if (!forfeit) { payments.createCancellationRefund(receipts); return !receipts.isEmpty(); }
        BigDecimal held = d.getHeldAmount();
        retain(d, now, FORFEIT_REASON);
        var refundable = new ArrayList<PaymentRequest>();
        BigDecimal rentPart = BigDecimal.ZERO; PaymentRequest combined = null;
        for (var p : receipts) {
            if (p.getId().equals(d.getInitialPaymentRequestId())) {
                if (p.getType() == PaymentType.RESERVATION_DEPOSIT) continue;
                combined = p; rentPart = p.getTotalAmount().subtract(held);
            } else refundable.add(p);
        }
        if (rentPart.signum() < 0) throw new IllegalStateException("Deposit ledger exceeds received amount; reconciliation required");
        payments.createCancellationRefund(refundable);
        if (combined != null && rentPart.signum() > 0) payments.createRentRefundObligation(combined, rentPart);
        return !refundable.isEmpty() || rentPart.signum() > 0;
    }
}
