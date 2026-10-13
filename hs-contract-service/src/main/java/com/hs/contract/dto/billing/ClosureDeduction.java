package com.hs.contract.dto.billing;
import java.math.BigDecimal;
import java.util.List;
public record ClosureDeduction(String description, BigDecimal amount, List<String> evidenceIds) {}
