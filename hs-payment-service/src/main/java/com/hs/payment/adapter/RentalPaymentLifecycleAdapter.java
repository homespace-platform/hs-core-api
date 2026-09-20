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
        BigDecimal monthlyCharges = req.getEstimatedMonthlyCharges() != null ? req.getEstimatedMonthlyCharges() : BigDecimal.ZERO;
        BigDecimal deposit = req.getDepositAmount() != null ? req.getDepositAmount() : BigDecimal.ZERO;
        BigDecimal total = req.getEstimatedInitialTotal() != null ? req.getEstimatedInitialTotal()
                : monthlyRent.add(monthlyCharges).add(deposit);

        paymentRequestService.createInitialPayment(
                req.getId(),
                listingId,
                req.getRenterId(),
                req.getOwnerId(),
                monthlyRent,
                monthlyCharges,
                deposit,
                total,
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
                .map(this::toSummary)
                .orElse(null);
    }

    @Override
    public Map<String, InitialPaymentSummary> getInitialPaymentSummaries(Collection<String> rentalRequestIds) {
        if (rentalRequestIds == null || rentalRequestIds.isEmpty()) {
            return Map.of();
        }
        return paymentRequestRepository.findByRentalRequestIdInAndType(rentalRequestIds, PaymentType.INITIAL)
                .stream()
                .collect(Collectors.toMap(PaymentRequest::getRentalRequestId, this::toSummary));
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

    private InitialPaymentSummary toSummary(PaymentRequest p) {
        return InitialPaymentSummary.builder()
                .id(p.getId())
                .status(p.getStatus() != null ? p.getStatus().name() : null)
                .totalAmount(p.getTotalAmount())
                .transferReference(p.getTransferReference())
                .expiresAt(p.getDueAt())
                .payerReportedAt(p.getPayerReportedAt())
                .payeeConfirmedAt(p.getPayeeConfirmedAt())
                .paidAt(p.getConfirmedAt())
                .confirmedAt(p.getConfirmedAt())
                .contractDueAt(p.getContractDueAt())
                .build();
    }
}
