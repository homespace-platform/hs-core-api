package com.hs.payment.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.time.BillingTime;
import com.hs.listing.model.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class) @MockitoSettings(strictness=Strictness.LENIENT)
class OnboardingPaymentCreationTest {
    @Mock PaymentRequestRepository paymentRequestRepository; @Mock PaymentLineItemRepository paymentLineItemRepository;
    @Mock PaymentEvidenceRepository paymentEvidenceRepository; @Mock PaymentEventRepository paymentEventRepository;
    @Mock DepositRecordRepository depositRecordRepository; @Mock BankAccountService bankAccountService;
    @Mock VietQrProvider vietQrProvider; @Mock TransferReferenceGenerator transferReferenceGenerator;
    @Mock StorageObjectRepository storageObjectRepository; @Mock PaymentProofUploadSessionService proofUploadSessionService;
    @Mock BillingTime billingTime; @Spy ObjectMapper objectMapper=new ObjectMapper(); @InjectMocks PaymentRequestService service;
    RentalRequest rental; Instant now=Instant.parse("2026-10-21T03:00:00Z");
    @BeforeEach void setup() {
        when(billingTime.now()).thenReturn(now);
        var bank=BankAccount.builder().bankBin("970422").bankCode("MB").bankName("MB").accountNumber("123456789").accountHolderName("TEST USER").build();
        when(bankAccountService.getDefaultIncomingAccount("owner")).thenReturn(bank);
        when(bankAccountService.getDefaultRefundAccount("tenant")).thenReturn(bank);
        when(transferReferenceGenerator.generateUniqueReference()).thenReturn("HSTEST123");
        when(paymentRequestRepository.save(any())).thenAnswer(i->{PaymentRequest p=i.getArgument(0);p.setId("payment");return p;});
        rental=RentalRequest.builder().id("rental").listing(Listing.builder().id("listing").build()).ownerId("owner").renterId("tenant")
            .monthlyRentPrice(new BigDecimal("5000000")).depositAmount(new BigDecimal("10000000")).build();
    }
    @Test void futureDepositContainsNoRentLine() {
        var p=service.createDeposit(rental,now.plusSeconds(3600));
        assertEquals(PaymentType.RESERVATION_DEPOSIT,p.getType());assertEquals(new BigDecimal("10000000"),p.getTotalAmount());
        verify(paymentLineItemRepository,never()).save(argThat(l->"RENT".equals(l.getType())));
        verify(paymentLineItemRepository).save(argThat(l->"DEPOSIT".equals(l.getType())));
    }
    @Test void firstRentDoesNotCreateAnotherDepositRecord() {
        when(paymentRequestRepository.findByRentalRequestIdAndType("rental",PaymentType.RESERVATION_DEPOSIT)).thenReturn(Optional.of(PaymentRequest.builder().id("deposit").build()));
        var p=service.createFirstRent(rental,now,now.plusSeconds(86400));assertEquals(new BigDecimal("5000000"),p.getTotalAmount());
        verifyNoInteractions(depositRecordRepository);
    }
    @Test void zeroDepositIsConfirmedExemptionWithoutZeroQr() {
        rental.setDepositAmount(BigDecimal.ZERO);var p=service.createDeposit(rental,now.plusSeconds(3600));
        assertEquals(PaymentStatus.CONFIRMED,p.getStatus());assertEquals("",p.getQrImageUrl());verifyNoInteractions(vietQrProvider);
        verify(depositRecordRepository).save(argThat(d->d.getStatus()==DepositStatus.HELD&&d.getOriginalAmount().signum()==0));
    }
    @Test void retriesReturnSameObligationAndDoNotCreateSecondQr() {
        var existing=PaymentRequest.builder().id("existing").build();
        when(paymentRequestRepository.findByRentalRequestIdAndType("rental",PaymentType.INITIAL)).thenReturn(Optional.of(existing));
        assertSame(existing,service.createFirstRent(rental,now,now.plusSeconds(86400)));verifyNoInteractions(vietQrProvider,depositRecordRepository);
    }
}
