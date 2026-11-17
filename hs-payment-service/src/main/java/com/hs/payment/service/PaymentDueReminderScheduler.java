package com.hs.payment.service;

import com.hs.common.time.BillingTime;
import com.hs.payment.model.constant.*;
import com.hs.payment.notification.PaymentNotificationWriter;
import com.hs.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Component @RequiredArgsConstructor @Slf4j
public class PaymentDueReminderScheduler {
    private final PaymentRequestRepository payments;
    private final PaymentNotificationWriter notifications;
    private final BillingTime time;
    private final PlatformTransactionManager transactions;
    private static final Set<PaymentStatus> UNPAID=Set.of(PaymentStatus.AWAITING_TRANSFER,PaymentStatus.REJECTED);

    @Scheduled(fixedDelay=60000)
    public void remindDueSoon() {
        Instant realNow=Instant.now(),billingNow=time.now();
        for(String id:payments.findDueSoonIds(UNPAID,PaymentTimePolicy.BILLING_TYPES,billingNow,billingNow.plus(Duration.ofHours(24)),
                realNow,realNow.plus(Duration.ofHours(24)),PageRequest.of(0,100))) {
            try { new TransactionTemplate(transactions).executeWithoutResult(tx->remindLocked(id,realNow,billingNow)); }
            catch(RuntimeException failure) { log.warn("Payment due reminder deferred ({})",failure.getClass().getSimpleName()); }
        }
    }
    void remindLocked(String id,Instant realNow,Instant billingNow) {
        payments.findByIdForUpdate(id).filter(p->Boolean.TRUE.equals(p.getActive()) && UNPAID.contains(p.getStatus())
                && p.getInvoiceId()==null && p.getType()!=PaymentType.MONTHLY_RENT && p.getTotalAmount()!=null && p.getTotalAmount().signum()>0).ifPresent(p->{
            Instant now=PaymentTimePolicy.usesBillingTime(p)?billingNow:realNow;
            if(p.getDueAt()==null || !p.getDueAt().isAfter(now) || p.getDueAt().isAfter(now.plus(Duration.ofHours(24)))
                    || p.getOpensAt()!=null && p.getOpensAt().isAfter(now) || Objects.equals(p.getReminderForDueAt(),p.getDueAt()))return;
            notifications.enqueue(p,PaymentNotificationWriter.Event.PAYMENT_DUE_SOON,p.getPayerId());
            p.setReminderForDueAt(p.getDueAt());payments.save(p);
        });
    }
}
