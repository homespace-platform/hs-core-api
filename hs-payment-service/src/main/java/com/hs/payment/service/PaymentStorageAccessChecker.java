package com.hs.payment.service;

import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.service.StorageAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentStorageAccessChecker implements StorageAccessChecker {

    private static final String PAYMENT_REQUEST_REFERENCE_TYPE = "PAYMENT_REQUEST";

    private final PaymentRequestRepository paymentRequestRepository;

    @Override
    public boolean canAccess(String userId, StorageObject object) {
        if (userId == null
                || object.getPurpose() != StoragePurpose.PAYMENT_PROOF
                || !PAYMENT_REQUEST_REFERENCE_TYPE.equalsIgnoreCase(object.getReferenceType())
                || object.getReferenceId() == null) {
            return false;
        }

        return paymentRequestRepository.findById(object.getReferenceId())
                .map(payment -> userId.equals(payment.getPayerId()) || userId.equals(payment.getPayeeId()))
                .orElse(false);
    }
}
