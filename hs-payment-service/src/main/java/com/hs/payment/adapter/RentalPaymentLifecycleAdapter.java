package com.hs.payment.adapter;

import com.hs.listing.dto.response.InitialPaymentSummary;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.port.RentalPaymentLifecyclePort;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RentalPaymentLifecycleAdapter implements RentalPaymentLifecyclePort {

    private final PaymentRequestService paymentRequestService;
    private final PaymentRequestRepository paymentRequestRepository;

    @Override
    public void createInitialPayment(RentalRequest req, Instant holdExpiresAt) {
        String listingId = req.getListing() != null ? req.getListing().getId() : null;
        BigDecimal monthlyRent = req.getEffectiveMonthlyRent() != null ? req.getEffectiveMonthlyRent()
                : (req.getMonthlyRentPrice() != null ? req.getMonthlyRentPrice() : BigDecimal.ZERO);
        BigDecimal deposit = req.getDepositAmount() != null ? req.getDepositAmount() : BigDecimal.ZERO;

        if(req.getMoveInDate().isAfter(java.time.LocalDate.ofInstant(paymentRequestService.businessNow(),com.hs.common.time.BillingTime.ZONE))) {
            paymentRequestService.createDeposit(req,holdExpiresAt); return;
        }
        paymentRequestService.createInitialPayment(
                req.getId(),
                listingId,
                req.getRenterId(),
                req.getOwnerId(),
                monthlyRent,
                deposit,
                req.getCostBreakdownSnapshot(),
                req.getExcludedChargesSnapshot(),
                holdExpiresAt
        );
    }

    @Override
    public boolean isHoldProtected(String rentalRequestId) {
        return paymentRequestService.isHoldProtected(rentalRequestId);
    }

    @Override
    public InitialPaymentSummary getInitialPaymentSummary(String rentalRequestId) {
        return paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId, PaymentType.INITIAL)
                .or(()->paymentRequestRepository.findByRentalRequestIdAndType(rentalRequestId,PaymentType.RESERVATION_DEPOSIT))
                .map(p->{
                    var all=paymentRequestRepository.findByRentalRequestIdAndTypeIn(rentalRequestId,java.util.List.of(PaymentType.INITIAL,PaymentType.RESERVATION_DEPOSIT));
                    return toSummary(p,all.stream().allMatch(PaymentRequestService::cancellableBeforeTransfer),
                        all.stream().anyMatch(row->row.getType()==PaymentType.RESERVATION_DEPOSIT));
                })
                .orElse(null);
    }

    @Override
    public Map<String, InitialPaymentSummary> getInitialPaymentSummaries(Collection<String> rentalRequestIds) {
        if (rentalRequestIds == null || rentalRequestIds.isEmpty()) {
            return Map.of();
        }
        var summaries=new java.util.HashMap<String,InitialPaymentSummary>();
        var all=new java.util.ArrayList<>(paymentRequestRepository.findByRentalRequestIdInAndType(rentalRequestIds,PaymentType.RESERVATION_DEPOSIT));
        all.addAll(paymentRequestRepository.findByRentalRequestIdInAndType(rentalRequestIds,PaymentType.INITIAL));
        var safe=all.stream().collect(Collectors.groupingBy(PaymentRequest::getRentalRequestId,
            Collectors.reducing(true,PaymentRequestService::cancellableBeforeTransfer,(a,b)->a&&b)));
        var separateDeposits=all.stream().filter(p->p.getType()==PaymentType.RESERVATION_DEPOSIT).map(PaymentRequest::getRentalRequestId).collect(Collectors.toSet());
        all.forEach(p->summaries.put(p.getRentalRequestId(),toSummary(p,Boolean.TRUE.equals(safe.get(p.getRentalRequestId())),separateDeposits.contains(p.getRentalRequestId()))));
        return summaries;
    }

    @Override
    public void cancelPayment(String rentalRequestId, String reason) {
        paymentRequestService.cancelPaymentByRentalRequest(rentalRequestId, reason);
    }

    @Override
    public void handleHoldExpired(String rentalRequestId) {
        paymentRequestService.handleHoldExpired(rentalRequestId);
    }

    @Override
    public boolean isConfirmed(String rentalRequestId) {
        return paymentRequestService.isConfirmed(rentalRequestId);
    }

    private InitialPaymentSummary toSummary(PaymentRequest p,boolean canCancel,boolean separateDeposit) {
        return InitialPaymentSummary.builder()
                .id(p.getId())
                .type(p.getType()!=null?p.getType().name():null)
                .displayName(p.getType()==PaymentType.RESERVATION_DEPOSIT?"Tiền cọc giữ chỗ":separateDeposit?"Tiền thuê tháng đầu":"Khoản thanh toán ban đầu")
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .totalAmount(p.getTotalAmount())
                .transferReference(p.getTransferReference())
                .expiresAt(p.getDueAt())
                .payerReportedAt(p.getPayerReportedAt())
                .confirmationDueAt(p.getConfirmationDueAt())
                .payeeConfirmedAt(p.getPayeeConfirmedAt())
                .paidAt(p.getConfirmedAt())
                .confirmedAt(p.getConfirmedAt())
                .contractDueAt(p.getContractDueAt())
                .canCancelImmediately(canCancel)
                .build();
    }
}
