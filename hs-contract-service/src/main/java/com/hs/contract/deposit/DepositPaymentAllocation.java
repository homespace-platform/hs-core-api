package com.hs.contract.deposit;

import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.listing.model.RentalRequest;
import java.math.BigDecimal;
import java.util.*;

/** A confirmed combined receipt is not entirely a deposit. No partial-payment inference. */
public record DepositPaymentAllocation(BigDecimal deposit,BigDecimal rent) {
    public static DepositPaymentAllocation verify(RentalRequest r,PaymentRequest p,DepositRecord ledger,List<PaymentLineItem> rows) {
        if(p==null || ledger==null || p.getStatus()!=PaymentStatus.CONFIRMED
            || !Set.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT).contains(p.getType())
            || p.getDirection()!=PaymentDirection.TENANT_TO_LANDLORD || !"VND".equals(p.getCurrency())
            || !Objects.equals(r.getId(),p.getRentalRequestId()) || !Objects.equals(r.getId(),ledger.getRentalRequestId())
            || !Objects.equals(p.getId(),ledger.getInitialPaymentRequestId())
            || !Objects.equals(r.getOwnerId(),p.getPayeeId()) || !Objects.equals(r.getRenterId(),p.getPayerId())
            || !Objects.equals(r.getListing().getId(),p.getListingId())
            || p.getPayerReportedAt()==null || p.getPayeeConfirmedAt()==null || p.getConfirmedAt()==null)
            throw new IllegalStateException("Khoản cọc chưa có xác nhận hợp lệ của hai bên.");
        BigDecimal deposit=BigDecimal.ZERO,rent=BigDecimal.ZERO;
        for(var row:rows) {
            if(!Objects.equals(row.getPaymentRequestId(),p.getId())||row.getAmount()==null||row.getAmount().signum()<0)throw new IllegalStateException("Phân bổ cọc không hợp lệ.");
            if("DEPOSIT".equals(row.getType()))deposit=deposit.add(row.getAmount());
            else if("RENT".equals(row.getType()))rent=rent.add(row.getAmount());
            else throw new IllegalStateException("Khoản thanh toán có dòng chưa được phân bổ.");
        }
        if(deposit.signum()<=0 || r.getDepositAmount()==null || ledger.getOriginalAmount()==null
            || deposit.compareTo(r.getDepositAmount())!=0 || deposit.compareTo(ledger.getOriginalAmount())!=0
            || p.getTotalAmount()==null || deposit.add(rent).compareTo(p.getTotalAmount())!=0
            || p.getType()==PaymentType.RESERVATION_DEPOSIT && rent.signum()!=0)
            throw new IllegalStateException("Phần cọc không khớp hồ sơ; cần đối soát, không kết xuất dữ liệu giả.");
        return new DepositPaymentAllocation(deposit,rent);
    }
}
