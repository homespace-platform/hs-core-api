package com.hs.contract.dto.billing;

import java.math.BigDecimal;

public record InvoiceLine(String type, String description, BigDecimal quantity,
                          BigDecimal unitPrice, BigDecimal amount) {}
