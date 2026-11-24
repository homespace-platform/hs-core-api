package com.hs.contract.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.hs.common.time.BillingTime;
import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.AgreementSigningNotificationWriter;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.constant.PaymentStatus;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Reminds the party whose signature is still missing shortly before the EXISTING signing deadline: the tenant
 * (lease and deposit agreement, {@code tenantSigningDueAt}) and the landlord of a lease (first-rent {@code contractDueAt}).
 * It only writes a notification; no deadline or state changes. Once per (document, party, deadline): see
 * {@link AgreementSigningNotificationWriter#dueSoon}. A moved deadline earns its own reminder.
 */
@Component @Slf4j
public class AgreementSigningReminderScheduler {
    private static final Set<ContractStatus> LANDLORD_TURN =
            Set.of(ContractStatus.DRAFT, ContractStatus.PENDING_REVIEW, ContractStatus.LANDLORD_SIGNATURE_PENDING);
    private final ContractRepository contracts;
    private final PaymentRequestRepository payments;
    private final RentalRequestRepository rentals;
    private final AgreementSigningNotificationWriter notices;
    private final BillingTime time;
    private final PlatformTransactionManager transactions;
    private final Duration lead;

    public AgreementSigningReminderScheduler(ContractRepository contracts, PaymentRequestRepository payments,
            RentalRequestRepository rentals, AgreementSigningNotificationWriter notices, BillingTime time,
            PlatformTransactionManager transactions, @Value("${rental.signing-reminder-hours:24}") long leadHours) {
        this.contracts = contracts; this.payments = payments; this.rentals = rentals; this.notices = notices;
        this.time = time; this.transactions = transactions; this.lead = Duration.ofHours(leadHours);
    }

    @Scheduled(fixedDelayString = "${rental.signing-reminder-check-ms:300000}")
    public void remind() {
        Instant now = time.now(), soon = now.plus(lead);
        sweep(true, (after, page) -> contracts.findIdsAwaitingTenantSignatureDue(now, soon, after, page), now, soon);
        sweep(false, (after, page) -> contracts.findIdsAwaitingLandlordLeaseSignatureDue(now, soon, after, page), now, soon);
    }

    private void sweep(boolean tenantSide, BiFunction<String, PageRequest, List<String>> source, Instant now, Instant soon) {
        String after = null;
        List<String> ids;
        do {
            ids = source.apply(after, PageRequest.of(0, 100));
            for (String id : ids) {
                try { new TransactionTemplate(transactions).executeWithoutResult(tx -> remindLocked(id, tenantSide, now, soon)); }
                catch (RuntimeException failure) { log.warn("Signing reminder deferred for contract {}: {}", id, failure.toString()); }
            }
            if (!ids.isEmpty()) after = ids.getLast();
        } while (ids.size() == 100);
    }

    void remindLocked(String contractId, boolean tenantSide, Instant now, Instant soon) {
        Contract c = contracts.findByIdForUpdate(contractId).orElse(null);
        if (c == null) return;
        Instant due; String recipient;
        if (tenantSide) {
            if (c.getStatus() != ContractStatus.TENANT_SIGNATURE_PENDING) return;
            due = c.getTenantSigningDueAt(); recipient = c.getTenantId();
        } else {
            if (!LANDLORD_TURN.contains(c.getStatus()) || c.getTenantSigningDueAt() != null || c.getRentalRequestId() == null) return;
            due = payments.findByRentalRequestIdAndType(c.getRentalRequestId(), PaymentType.INITIAL)
                    .filter(p -> p.getStatus() == PaymentStatus.CONFIRMED).map(p -> p.getContractDueAt()).orElse(null);
            recipient = c.getLandlordId();
        }
        if (due == null || !due.isAfter(now) || due.isAfter(soon)) return;
        if (c.getRentalRequestId() != null) {
            var r = rentals.findById(c.getRentalRequestId()).orElse(null);
            // Paused/closed flows cannot be signed, and a default notice already carries its own final deadline.
            if (r == null || r.getStatus() != RentalRequestStatus.ACCEPTED || r.getCancellationRequestedAt() != null
                    || r.getResolutionWorkflowId() != null || due.equals(r.getDefaultRespondBy())) return;
        }
        notices.dueSoon(c, recipient, due);
    }
}
