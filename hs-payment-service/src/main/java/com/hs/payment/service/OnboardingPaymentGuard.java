package com.hs.payment.service;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.PaymentType;
import com.hs.payment.advice.PaymentErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shared by web and phone evidence upload, independent of the upload service. */
@Component @RequiredArgsConstructor
public class OnboardingPaymentGuard {
    private final BillingTime time;
    private final RentalRequestRepository rentals;
    public void validate(PaymentRequest p) {
        if(p.getType()!=PaymentType.INITIAL&&p.getType()!=PaymentType.RESERVATION_DEPOSIT)return;
        var r=rentals.findByIdForUpdate(p.getRentalRequestId()).orElseThrow(()->new AppException(PaymentErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        if(r.getStatus()!=RentalRequestStatus.ACCEPTED)throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS);
        if(r.getCancellationRequestedAt()!=null)throw new AppException(PaymentErrorCode.INVALID_PAYMENT_STATUS,"Hồ sơ đang chờ quyết định hủy; chưa tiếp nhận khoản chuyển mới.");
        if(p.getOpensAt()!=null&&time.now().isBefore(p.getOpensAt())||p.getDueAt()!=null&&!time.now().isBefore(p.getDueAt()))
            throw new AppException(PaymentErrorCode.PAYMENT_HOLD_EXPIRED,"Ngoài thời gian thanh toán. Nếu đã chuyển tiền, hãy yêu cầu đối soát.");
    }
}
