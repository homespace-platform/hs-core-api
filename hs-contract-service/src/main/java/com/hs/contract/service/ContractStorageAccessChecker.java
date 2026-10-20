package com.hs.contract.service;

import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.service.StorageAccessChecker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ContractStorageAccessChecker implements StorageAccessChecker {

    private static final String CONTRACT_REFERENCE_TYPE = "CONTRACT";

    private final ContractRepository contractRepository;
    private final RentalRequestRepository rentalRequestRepository;
    private final PaymentRequestRepository paymentRequestRepository;

    @Autowired
    public ContractStorageAccessChecker(ContractRepository contractRepository,
            RentalRequestRepository rentalRequestRepository, PaymentRequestRepository paymentRequestRepository) {
        this.contractRepository = contractRepository;
        this.rentalRequestRepository = rentalRequestRepository;
        this.paymentRequestRepository = paymentRequestRepository;
    }

    /** Without the onboarding repositories a tenant never reads a root draft (the old, stricter rule). */
    public ContractStorageAccessChecker(ContractRepository contractRepository) {
        this(contractRepository, null, null);
    }

    @Override
    public boolean canAccess(String userId, StorageObject object) {
        if (userId == null
                || object.getPurpose() != StoragePurpose.CONTRACT_DOCUMENT
                || !CONTRACT_REFERENCE_TYPE.equals(object.getReferenceType())
                || object.getReferenceId() == null) {
            return false;
        }

        return contractRepository.findById(object.getReferenceId())
                .map(contract -> userId.equals(contract.getLandlordId())
                        || (userId.equals(contract.getTenantId())
                        && (contract.getStatus() != ContractStatus.DRAFT
                            || "AMENDMENT".equals(contract.getAgreementKind())
                            // Deposit-confirmed root draft of the matching ACCEPTED rental: read-only preview.
                            || ContractDraftVisibility.tenantMayReview(contract, rentalRequestRepository, paymentRequestRepository))))
                .orElse(false);
    }
}
