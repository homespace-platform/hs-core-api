package com.hs.contract.service;

import com.hs.listing.model.RentalRequest;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import java.util.Objects;

/** A reported transfer is not received money. A confirmed zero deposit represents a waiver. */
public final class ContractDraftFunding {
    private ContractDraftFunding() {}

    public static boolean ready(RentalRequest rental, PaymentRequest rent, PaymentRequest deposit) {
        if (!received(rental, rent, PaymentType.INITIAL)
                || deposit != null && !received(rental, deposit, PaymentType.RESERVATION_DEPOSIT)) return false;
        var expectedRent = rental.getEffectiveMonthlyRent() != null ? rental.getEffectiveMonthlyRent() : rental.getMonthlyRentPrice();
        var expectedDeposit = rental.getDepositAmount();
        if (expectedRent == null || expectedDeposit == null || expectedRent.signum() < 0 || expectedDeposit.signum() < 0) return false;
        return deposit == null
                ? rent.getTotalAmount().compareTo(expectedRent.add(expectedDeposit)) == 0
                : rent.getTotalAmount().compareTo(expectedRent) == 0 && deposit.getTotalAmount().compareTo(expectedDeposit) == 0;
    }

    private static boolean received(RentalRequest rental, PaymentRequest payment, PaymentType type) {
        return payment != null && payment.getType() == type && payment.getStatus() == PaymentStatus.CONFIRMED
                && payment.getDirection() == PaymentDirection.TENANT_TO_LANDLORD
                && Objects.equals(rental.getId(), payment.getRentalRequestId())
                && Objects.equals(rental.getRenterId(), payment.getPayerId())
                && Objects.equals(rental.getOwnerId(), payment.getPayeeId())
                && payment.getTotalAmount() != null && payment.getTotalAmount().signum() >= 0;
    }
}
