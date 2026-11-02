package com.hs.payment.event;
import com.hs.payment.model.constant.*;
/** Synchronous event: the case must be persisted in the same transaction as DISPUTED. */
public record PaymentDisputed(String paymentId,String actorId,String reason,PaymentStatus previousStatus,DepositStatus previousDepositStatus) {}
