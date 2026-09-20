package com.hs.listing.port;

import com.hs.listing.dto.response.InitialPaymentSummary;
import com.hs.listing.model.RentalRequest;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

public interface RentalPaymentLifecyclePort {

    void createInitialPayment(RentalRequest rentalRequest, Instant holdExpiresAt);

    boolean isHoldProtected(String rentalRequestId);

    InitialPaymentSummary getInitialPaymentSummary(String rentalRequestId);

    Map<String, InitialPaymentSummary> getInitialPaymentSummaries(Collection<String> rentalRequestIds);

    void cancelPayment(String rentalRequestId, String reason);

    void handleHoldExpired(String rentalRequestId);

    boolean isConfirmed(String rentalRequestId);
}
