package com.hs.contract.dto.ai;

import com.hs.contract.model.constant.ContractStatus;

import java.time.Instant;
import java.time.LocalDate;

/** Lightweight projection used to count/filter a user's root contracts without loading entities. */
public record ContractLeaseRow(String id, String contractNumber, ContractStatus status, String landlordId,
                               String tenantId, Instant createdAt, Instant signedAt, Integer effectiveLeaseMonths,
                               LocalDate moveInDate, Integer requestedLeaseMonths, String currentRevisionId) {}
