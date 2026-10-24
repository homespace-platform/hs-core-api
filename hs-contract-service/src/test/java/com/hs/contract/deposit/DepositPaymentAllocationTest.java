package com.hs.contract.deposit;

import com.hs.listing.model.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DepositPaymentAllocationTest {
    final RentalRequest rental=RentalRequest.builder().id("r").ownerId("a").renterId("b").depositAmount(new BigDecimal("4000000")).listing(Listing.builder().id("l").build()).build();
    final PaymentRequest payment=PaymentRequest.builder().id("p").rentalRequestId("r").listingId("l").payerId("b").payeeId("a").type(PaymentType.INITIAL)
        .direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).currency("VND").totalAmount(new BigDecimal("12000000"))
        .payerReportedAt(Instant.now()).payeeConfirmedAt(Instant.now()).confirmedAt(Instant.now()).build();
    final DepositRecord ledger=DepositRecord.builder().rentalRequestId("r").initialPaymentRequestId("p").originalAmount(new BigDecimal("4000000")).build();
    PaymentLineItem row(String kind,String amount){return PaymentLineItem.builder().paymentRequestId("p").type(kind).amount(new BigDecimal(amount)).build();}
    @Test void sameDayCombinedReceiptAllocatesOnlyDeposit(){var result=DepositPaymentAllocation.verify(rental,payment,ledger,List.of(row("DEPOSIT","4000000"),row("RENT","8000000")));assertEquals(new BigDecimal("4000000"),result.deposit());assertEquals(new BigDecimal("8000000"),result.rent());}
    @Test void separateDepositDoesNotChargeRent(){payment.setType(PaymentType.RESERVATION_DEPOSIT);payment.setTotalAmount(new BigDecimal("4000000"));assertEquals(0,DepositPaymentAllocation.verify(rental,payment,ledger,List.of(row("DEPOSIT","4000000"))).rent().signum());}
    @Test void merelyReportedReceiptCannotProduceReceivedDeposit(){payment.setStatus(PaymentStatus.TRANSFER_REPORTED);assertThrows(IllegalStateException.class,()->DepositPaymentAllocation.verify(rental,payment,ledger,List.of(row("DEPOSIT","4000000"),row("RENT","8000000"))));}
    @Test void wrongLedgerOrPartiesCannotBorrowAnotherReceipt(){ledger.setInitialPaymentRequestId("another");assertThrows(IllegalStateException.class,()->DepositPaymentAllocation.verify(rental,payment,ledger,List.of(row("DEPOSIT","4000000"),row("RENT","8000000"))));}
    @Test void mismatchedOrPartialDepositNeverPretendsToBeFull(){assertThrows(IllegalStateException.class,()->DepositPaymentAllocation.verify(rental,payment,ledger,List.of(row("DEPOSIT","3000000"),row("RENT","9000000"))));}
    @Test void zeroWaiverDoesNotBecomeBankReceipt(){rental.setDepositAmount(BigDecimal.ZERO);ledger.setOriginalAmount(BigDecimal.ZERO);payment.setTotalAmount(BigDecimal.ZERO);payment.setPayerReportedAt(null);assertThrows(IllegalStateException.class,()->DepositPaymentAllocation.verify(rental,payment,ledger,List.of()));}
}
