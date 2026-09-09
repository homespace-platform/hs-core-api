package com.hs.contract.service;

import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.ContractRepository;
import com.hs.listing.service.RentalHoldProtectionChecker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class ContractRentalHoldProtectionChecker implements RentalHoldProtectionChecker {

    private static final Set<ContractStatus> PROTECTED_STATUSES =
            Set.of(ContractStatus.PENDING_REVIEW, ContractStatus.ACTIVE);

    private final ContractRepository contractRepository;

    @Override
    public boolean isProtected(String rentalRequestId) {
        return rentalRequestId != null
                && contractRepository.existsByRentalRequestIdAndStatusIn(rentalRequestId, PROTECTED_STATUSES);
    }
}
