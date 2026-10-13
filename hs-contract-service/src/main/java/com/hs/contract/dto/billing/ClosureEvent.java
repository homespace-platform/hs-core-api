package com.hs.contract.dto.billing;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
public record ClosureEvent(String type, int proposalVersion, Instant at, String actorId, String note,
                           List<ClosureDeduction> deductions, BigDecimal refundAmount, BigDecimal balanceAmount,
                           List<String> evidenceIds) {}
