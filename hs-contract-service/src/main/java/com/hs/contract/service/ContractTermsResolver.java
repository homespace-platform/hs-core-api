package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.model.*;
import com.hs.contract.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** Signed, effective-dated snapshots; the root's signed revision never changes. */
@Service @RequiredArgsConstructor
public class ContractTermsResolver {
    private final ContractAmendmentRepository amendments;
    private final ContractRevisionRepository revisions;

    public ContractRevision at(Contract root, LocalDate periodStart) {
        return selectAmendment(amendments.findByContractIdOrderByCreatedAtDesc(root.getId()), periodStart)
                .map(a -> require(a.getTermsRevisionId())).orElseGet(() -> require(root.getCurrentRevisionId()));
    }

    /**
     * Pure selection rule shared with read-only consumers: the signed, scheduled/applied amendment with the
     * latest effective date that is not after {@code date}. Status labels that a scheduler has not refreshed yet
     * do not matter; only signature and effective date do. Superseded/rejected/unsigned proposals never qualify.
     */
    public static Optional<ContractAmendment> selectAmendment(Collection<ContractAmendment> history, LocalDate date) {
        return history.stream()
                .filter(a -> Set.of("SCHEDULED", "APPLIED").contains(a.getStatus()) && a.getSignedAt() != null
                        && a.getTermsRevisionId() != null && !a.getEffectiveOn().isAfter(date))
                .max(Comparator.comparing(ContractAmendment::getEffectiveOn)
                        .thenComparing(ContractAmendment::getSignedAt));
    }

    public ContractRevision require(String id) {
        return revisions.findById(id).orElseThrow(() -> new AppException(ContractErrorCode.INVOICE_TERMS_INCOMPLETE));
    }
}
