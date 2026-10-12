package com.hs.contract.dto.billing;

import java.math.BigDecimal;

public record InvoiceLine(String type, String description, BigDecimal quantity,
                          BigDecimal unitPrice, BigDecimal amount, String quantityUnit) {
    public InvoiceLine(String type, String description, BigDecimal quantity,
                       BigDecimal unitPrice, BigDecimal amount) {
        this(type, description, quantity, unitPrice, amount, null);
    }
}
