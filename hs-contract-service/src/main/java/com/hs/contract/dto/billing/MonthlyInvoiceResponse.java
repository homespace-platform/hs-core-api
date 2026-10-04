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
                                     Instant issuedAt, Instant dueAt, Instant paidAt) {}
