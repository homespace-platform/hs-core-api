package com.hs.contract.dto.billing;

import com.hs.contract.model.MonthlyInvoiceStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MonthlyInvoiceResponse(String id, String contractId, int periodIndex,
                                     LocalDate periodStart, LocalDate periodEndExclusive,
                                     MonthlyInvoiceStatus status, BigDecimal electricityStart,
                                     BigDecimal electricityEnd, BigDecimal waterStart,
                                     BigDecimal waterEnd, List<InvoiceLine> lines,
                                     BigDecimal totalAmount, String paymentRequestId,
                                     Instant issuedAt, Instant dueAt, Instant paidAt,
                                     BigDecimal lateFeeAmount, Instant serverNow,
                                     Instant meterDeadlineAt, String workflowState,
                                     List<IssueMonthlyInvoiceRequest.ExtraCharge> draftExtraCharges,
                                     List<OverdueAction> overdueActions,
                                     Instant deferredAt, String rolledToInvoiceId,
                                     String terminationProposalInvoiceId, Instant terminationProposedAt, Instant terminationAcceptedAt,
                                     Instant terminationDeclinedAt,
                                     Instant terminationCancelledAt,
                                     Instant terminationCompletedAt, BigDecimal originalDepositAmount,
                                     BigDecimal retainedDepositAmount,
                                     boolean canDeferToNextPeriod,
                                     boolean landlordTerminationClauseSigned,
                                     Instant terminationForcedAt) {}
