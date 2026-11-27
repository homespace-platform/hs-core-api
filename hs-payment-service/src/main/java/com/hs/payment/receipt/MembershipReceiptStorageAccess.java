package com.hs.payment.receipt;

import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.service.StorageAccessChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Only the buyer may open a membership receipt PDF. */
@Component
@RequiredArgsConstructor
public class MembershipReceiptStorageAccess implements StorageAccessChecker {
  static final String REFERENCE_TYPE = "MEMBERSHIP_RECEIPT";
  private final MembershipReceiptRepository receipts;

  @Override
  @Transactional(readOnly = true)
  public boolean canAccess(String userId, StorageObject object) {
    if (userId == null
        || object.getPurpose() != StoragePurpose.PAYMENT_RECEIPT
        || object.getVisibility() != StorageVisibility.PRIVATE
        || !REFERENCE_TYPE.equals(object.getReferenceType())) return false;
    return receipts
        .findById(object.getReferenceId())
        .filter(r -> object.getId().equals(r.getStorageObjectId()) && userId.equals(r.getOwnerId()))
        .isPresent();
  }
}
