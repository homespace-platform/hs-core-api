package com.hs.payment.receipt;

import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentReceiptStorageAccess implements StorageAccessChecker {
  private final PaymentReceiptRepository receipts;

  @Override
  @Transactional(readOnly = true)
  public boolean canAccess(String userId, StorageObject object) {
    if (userId == null
        || object.getPurpose() != StoragePurpose.PAYMENT_RECEIPT
        || object.getVisibility() != StorageVisibility.PRIVATE
        || !"PAYMENT_RECEIPT".equals(object.getReferenceType())) return false;
    return receipts
        .findById(object.getReferenceId())
        .filter(
            r ->
                object.getId().equals(r.getStorageObjectId())
                    && (userId.equals(r.getLandlordId()) || userId.equals(r.getTenantId())))
        .isPresent();
  }

  @Override
  public boolean preventsDeletion(StorageObject object) {
    return object.getPurpose() == StoragePurpose.PAYMENT_RECEIPT;
  }
}
