package com.hs.payment.adapter;

import com.hs.listing.dto.estimate.RentalPaymentPlan;
import com.hs.listing.model.Listing;
import com.hs.listing.model.RentalRequest;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.payment.service.PaymentRequestService;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class RentalPaymentPlanParityTest {
    @ParameterizedTest @CsvSource({"2026-10-21T03:00:00Z,true", "2026-10-31T16:59:59Z,true", "2026-10-31T17:00:00Z,false"})
    void estimateScheduleMatchesActualPaymentCreationOnAcceptance(String value,boolean depositOnly) {
        var now=Instant.parse(value);var start=LocalDate.of(2026,11,1);
        var rent=new BigDecimal("19660000");var deposit=new BigDecimal("39320000");
        var plan=RentalPaymentPlan.at(now,start,rent,deposit);
        assertEquals(depositOnly,plan.depositOnly());
        var payments=mock(PaymentRequestService.class);when(payments.businessNow()).thenReturn(now);
        var request=RentalRequest.builder().id("rental").listing(Listing.builder().id("listing").build())
            .ownerId("owner").renterId("tenant").moveInDate(start).effectiveMonthlyRent(rent).depositAmount(deposit).build();
        new RentalPaymentLifecycleAdapter(payments,mock(PaymentRequestRepository.class)).createInitialPayment(request,now.plusSeconds(3600));
        if(depositOnly)verify(payments).createDeposit(request,now.plusSeconds(3600));
        else verify(payments).createInitialPayment("rental","listing","tenant","owner",rent,deposit,null,null,now.plusSeconds(3600));
        verifyNoMoreInteractions(ignoreStubs(payments));
    }
}
