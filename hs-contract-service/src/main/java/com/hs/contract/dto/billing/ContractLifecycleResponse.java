package com.hs.contract.dto.billing;
import com.hs.contract.model.*;
import com.hs.payment.dto.PaymentRequestResponse;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
public record ContractLifecycleResponse(Instant serverNow, LocalDate endExclusive, Instant renewalOpensAt,
    Instant renewalSignBy, boolean canRequestRenewal, List<ContractRenewal> renewals, ContractClosure closure,
    BigDecimal heldDeposit, boolean canProposeClosure, PaymentRequestResponse refundPayment,
    PaymentRequestResponse balancePayment, ClosureBillingResponse billing) {}
