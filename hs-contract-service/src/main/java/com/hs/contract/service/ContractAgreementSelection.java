package com.hs.contract.service;

import com.hs.contract.model.Contract;
import com.hs.contract.model.constant.ContractPartyRole;
import jakarta.persistence.criteria.*;

/** Select one main agreement per rental before filtering and pagination; billing stays lease-only. */
public final class ContractAgreementSelection {
    private ContractAgreementSelection() {}

    public static Predicate lease(Path<Contract> c, CriteriaBuilder cb) {
        return cb.or(cb.isNull(c.get("agreementKind")), cb.notEqual(c.get("agreementKind"), "DEPOSIT"));
    }

    public static Predicate primary(Root<Contract> c, CommonAbstractCriteria query, CriteriaBuilder cb,
                                    String actor, ContractPartyRole role) {
        var existing = query.subquery(Integer.class);
        var other = existing.from(Contract.class);
        var readable = role == ContractPartyRole.LANDLORD ? cb.conjunction()
            : cb.or(cb.equal(other.get("landlordId"), actor), ContractDraftVisibility.tenantReadable(other, existing, cb));
        existing.select(cb.literal(1)).where(
            cb.equal(other.get("rentalRequestId"), c.get("rentalRequestId")),
            cb.equal(other.get("landlordId"), c.get("landlordId")),
            cb.equal(other.get("tenantId"), c.get("tenantId")),
            cb.isTrue(other.get("active")), cb.isNull(other.get("parentContractId")), lease(other, cb), readable);
        return cb.or(lease(c, cb), cb.and(cb.equal(c.get("agreementKind"), "DEPOSIT"), cb.not(cb.exists(existing))));
    }
}
