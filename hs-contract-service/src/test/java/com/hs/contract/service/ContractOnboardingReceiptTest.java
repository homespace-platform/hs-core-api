package com.hs.contract.service;

import com.hs.contract.service.engine.ContractDataBuilder;
import com.hs.listing.model.RentalRequest;
import com.hs.payment.model.PaymentRequest;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.PaymentRequestRepository;
import com.hs.user.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractOnboardingReceiptTest {
    PaymentRequestRepository payments=mock(PaymentRequestRepository.class);
    ContractDataBuilder builder=new ContractDataBuilder(mock(UserRepository.class),mock(AddressRepository.class));
    RentalRequest rental=RentalRequest.builder().id("rental").monthlyRentPrice(new BigDecimal("5000000")).depositAmount(new BigDecimal("10000000")).build();
    PaymentRequest deposit=PaymentRequest.builder().id("deposit").type(PaymentType.RESERVATION_DEPOSIT).status(PaymentStatus.CONFIRMED)
        .totalAmount(new BigDecimal("10000000")).transferReference("HSDEPOSIT").confirmedAt(Instant.parse("2026-10-21T03:00:00Z")).build();
    @BeforeEach void setup() {
        ReflectionTestUtils.setField(builder,"payments",payments);
        when(payments.findByRentalRequestIdAndType("rental",PaymentType.RESERVATION_DEPOSIT)).thenReturn(Optional.of(deposit));
    }
    @Test void depositReceiptDoesNotClaimThatRentWasPaid() {
        var snapshot=builder.onboardingPayment(rental,deposit,Map.of(),List.of());
        assertEquals("Chưa xác nhận đủ tiền tháng đầu và tiền cọc",snapshot.get("status"));
        assertEquals("Chưa mở thanh toán",snapshot.get("rentReceipt"));
        assertTrue(snapshot.get("depositReceipt").toString().contains("21/10/2026 10:00:00"));
        assertEquals("",snapshot.get("confirmedAt"));
    }
    @Test void completedReceiptsKeepTwoIndependentReferencesAndTimes() {
        var rent=PaymentRequest.builder().type(PaymentType.INITIAL).status(PaymentStatus.CONFIRMED).transferReference("HSRENT")
            .totalAmount(new BigDecimal("5000000")).confirmedAt(Instant.parse("2026-11-01T03:00:00Z")).build();
        when(payments.findByRentalRequestIdAndType("rental",PaymentType.INITIAL)).thenReturn(Optional.of(rent));
        var snapshot=builder.onboardingPayment(rental,rent,Map.of(),List.of());
        assertEquals("Hai khoản đã được xác nhận",snapshot.get("status"));
        assertTrue(snapshot.get("transferReference").toString().contains("HSDEPOSIT"));
        assertTrue(snapshot.get("transferReference").toString().contains("HSRENT"));
        assertTrue(snapshot.get("rentReceipt").toString().contains("01/11/2026 10:00:00"));
    }
    @Test void waivedDepositNeverInventsABankReceipt() {
        deposit.setTotalAmount(BigDecimal.ZERO);
        assertEquals("Không yêu cầu thanh toán; không phát sinh chuyển khoản",builder.onboardingPayment(rental,deposit,Map.of(),List.of()).get("depositReceipt"));
    }
}
