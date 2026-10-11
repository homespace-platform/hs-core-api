package com.hs.contract.service;

import com.hs.contract.model.ContractRevision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class ContractPreviewIdentity {
    private ContractPreviewIdentity() {}
    public static String fingerprint(ContractRevision revision, String signatureMode) {
        // Bump when renderer semantics change. Legacy READY files cannot satisfy a new render.
        String identity = String.join("\u0000", "policy-snapshot-v1",
                Objects.toString(signatureMode, "INTERNAL"), Objects.toString(revision.getId(), ""),
                Objects.toString(revision.getTemplateVersionId(), ""),
                Objects.toString(revision.getLandlordSnapshot(), ""), Objects.toString(revision.getTenantSnapshot(), ""),
                Objects.toString(revision.getPropertySnapshot(), ""), Objects.toString(revision.getLeaseSnapshot(), ""),
                Objects.toString(revision.getFinancialSnapshot(), ""), Objects.toString(revision.getChargesSnapshot(), ""),
                Objects.toString(revision.getEquipmentSnapshot(), ""), Objects.toString(revision.getAmenitiesSnapshot(), ""),
                Objects.toString(revision.getInitialPaymentSnapshot(), ""), Objects.toString(revision.getInitialMetersSnapshot(), ""),
                Objects.toString(revision.getPoliciesSnapshot(), ""), Objects.toString(revision.getSpecialTerms(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
