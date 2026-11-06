package com.hs.payment.service;

import com.hs.payment.dto.DepositLedgerView;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;

/** Internal read model; callers enforce access to the rental/contract before reading it. */
@Service @RequiredArgsConstructor
public class DepositLedgerService {
    private final DepositRecordRepository deposits;
    private final PaymentRequestRepository payments;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Transactional(readOnly=true)
    public DepositLedgerView forRental(String rentalId) {
        var d=deposits.findByRentalRequestId(rentalId).orElse(null);
        if(d==null)return null;
        var originalPayment=java.util.Optional.ofNullable(d.getInitialPaymentRequestId()).flatMap(payments::findById).filter(p->p.getStatus()==PaymentStatus.CONFIRMED
                && (p.getType()==PaymentType.INITIAL||p.getType()==PaymentType.RESERVATION_DEPOSIT)
                && p.getDirection()==PaymentDirection.TENANT_TO_LANDLORD
                && rentalId.equals(p.getRentalRequestId()) && java.util.Objects.equals(d.getTenantId(),p.getPayerId())
                && java.util.Objects.equals(d.getLandlordId(),p.getPayeeId()));
        BigDecimal received=d.getOriginalAmount()!=null&&d.getOriginalAmount().signum()==0?BigDecimal.ZERO:
                originalPayment.map(p->d.getOriginalAmount()).orElse(null);
        BigDecimal refunded=BigDecimal.ZERO;
        boolean finalRefundConfirmed=false;
        for(var p:payments.findByRentalRequestIdAndTypeIn(rentalId,List.of(PaymentType.DEPOSIT_ADJUSTMENT,PaymentType.DEPOSIT_REFUND,PaymentType.INITIAL_PAYMENT_REFUND))) {
            if(p.getStatus()!=PaymentStatus.CONFIRMED)continue;
            if(p.getType()==PaymentType.DEPOSIT_ADJUSTMENT && p.getDirection()==PaymentDirection.TENANT_TO_LANDLORD
                    && d.getTenantId().equals(p.getPayerId())&&d.getLandlordId().equals(p.getPayeeId())) {
                if(received!=null)received=received.add(p.getTotalAmount());
            } else if(p.getDirection()==PaymentDirection.LANDLORD_TO_TENANT && d.getLandlordId().equals(p.getPayerId())
                    && d.getTenantId().equals(p.getPayeeId())
                    && (p.getType()==PaymentType.DEPOSIT_ADJUSTMENT||p.getId().equals(d.getRefundPaymentRequestId()))) {
                if(p.getType()==PaymentType.INITIAL_PAYMENT_REFUND) {
                    // A combined initial refund contains rent too. Count only its verified deposit principal.
                    boolean fullSourceRefund=originalPayment.filter(source->source.getTotalAmount()!=null
                            && (source.getTotalAmount().compareTo(p.getTotalAmount())==0
                                && ("REFUND:"+source.getId()).equals(p.getOnboardingKey())
                                || InitialRefundSources.covers(p,source,objectMapper))).isPresent();
                    if(fullSourceRefund && received!=null && d.getOriginalAmount()!=null && d.getOriginalAmount().signum()>0
                            && d.getOriginalAmount().compareTo(p.getTotalAmount())<=0) {
                        refunded=refunded.add(d.getOriginalAmount());finalRefundConfirmed=true;
                    }
                } else {
                    refunded=refunded.add(p.getTotalAmount());
                    if(p.getType()==PaymentType.DEPOSIT_REFUND)finalRefundConfirmed=true;
                }
            }
        }
        // A legacy REFUNDED label without a matching receipt cannot establish how much was returned.
        if(d.getStatus()==DepositStatus.REFUNDED && !finalRefundConfirmed)refunded=null;
        return new DepositLedgerView(d.getStatus().name(),d.getOriginalAmount(),d.getHeldAmount(),d.getRetainedAmount(),
            d.getRefundableAmount(),received,refunded,d.getRetainedAt(),d.getRetainedReason());
    }
}
