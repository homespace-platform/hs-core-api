package com.hs.payment.config;

import com.hs.common.time.BillingTime;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.RentalRequestStatus;
import com.hs.listing.repository.RentalRequestRepository;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real-database check of the startup clean-up left behind by the removed payment dispute feature. */
@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql=false) @AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER}",
    "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import(LegacyPaymentDisputeNormalizer.class)
class LegacyPaymentDisputeNormalizerPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs") @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses=PaymentRequestRepository.class)
    static class Config {}

    @Autowired LegacyPaymentDisputeNormalizer normalizer;
    @Autowired PaymentRequestRepository payments;
    @Autowired PaymentEventRepository events;
    @Autowired PaymentEvidenceRepository evidence;
    @Autowired DepositRecordRepository deposits;
    @Autowired DisputeCaseRepository cases;
    @Autowired DisputeCaseEventRepository caseEvents;
    @MockitoBean RentalRequestRepository rentals;
    @MockitoBean BillingTime time;
    final Instant now = Instant.parse("2026-11-24T03:00:00Z");

    @BeforeEach void clean() {
        caseEvents.deleteAllInBatch(); cases.deleteAllInBatch(); events.deleteAllInBatch(); evidence.deleteAllInBatch();
        deposits.deleteAllInBatch(); payments.deleteAllInBatch();
        when(time.now()).thenReturn(now);
    }

    PaymentRequest disputed(PaymentType type, Instant dueAt, String rentalId) {
        return payments.saveAndFlush(PaymentRequest.builder().payerId("payer").payeeId("payee").type(type)
            .direction(type == PaymentType.DEPOSIT_REFUND ? PaymentDirection.LANDLORD_TO_TENANT : PaymentDirection.TENANT_TO_LANDLORD)
            .status(PaymentStatus.DISPUTED).currency("VND").totalAmount(new BigDecimal("8800000")).dueAt(dueAt).rentalRequestId(rentalId)
            .transferReference("HS" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)).build());
    }

    DisputeCase openCase(String subjectType, String subjectId, PaymentStatus previous) {
        return cases.saveAndFlush(DisputeCase.builder().subjectType(subjectType).subjectId(subjectId).activeKey(subjectType + ":" + subjectId)
            .payerId("payer").payeeId("payee").openedBy("payer").reason("Mở hồ sơ để kiểm tra").openedAt(now.minusSeconds(600))
            .status(DisputeCaseStatus.ESCALATED).previousPaymentStatus(previous).amountSnapshot(new BigDecimal("8800000"))
            .currency("VND").initialEvidenceSnapshot("[]").revision(3).build());
    }

    PaymentRequest reload(PaymentRequest p) { return payments.findById(p.getId()).orElseThrow(); }

    @Test void restoresPreviousStatusResolvesPaymentCaseAndLeavesComplaintsAlone() {
        var p = disputed(PaymentType.MONTHLY_RENT, now.plus(Duration.ofDays(2)), null);
        var paymentCase = openCase("PAYMENT", p.getId(), PaymentStatus.TRANSFER_REPORTED);
        var complaint = openCase("RENTAL", "rental-1", null);

        normalizer.normalize();

        var after = reload(p);
        assertEquals(PaymentStatus.TRANSFER_REPORTED, after.getStatus());
        assertEquals(now.plus(Duration.ofHours(24)), after.getConfirmationDueAt());
        var resolved = cases.findById(paymentCase.getId()).orElseThrow();
        assertEquals(DisputeCaseStatus.RESOLVED, resolved.getStatus());
        assertNull(resolved.getActiveKey());
        assertEquals(LegacyPaymentDisputeNormalizer.REASON, resolved.getResolutionReason());
        assertEquals(1, caseEvents.findAll().stream().filter(e -> e.getCaseId().equals(paymentCase.getId()) && e.getAction().equals("RESOLVED")).count());
        var untouched = cases.findById(complaint.getId()).orElseThrow();
        assertEquals(DisputeCaseStatus.ESCALATED, untouched.getStatus());
        assertEquals("RENTAL:rental-1", untouched.getActiveKey());
        var routed = events.findAll().stream().filter(e -> e.getPaymentRequestId().equals(p.getId())).toList();
        assertEquals(1, routed.size());
        assertEquals(PaymentEventType.DISPUTE_ROUTED, routed.getFirst().getEventType());
        assertEquals(PaymentStatus.TRANSFER_REPORTED, routed.getFirst().getToStatus());

        // Idempotent: nothing is DISPUTED any more, so a second boot changes nothing.
        normalizer.normalize();
        assertEquals(1, events.count());
        assertEquals(1, caseEvents.count());
    }

    @Test void pastDueBillingPaymentWithoutCaseOrProofBecomesOverdue() {
        var p = disputed(PaymentType.MONTHLY_RENT, now.minus(Duration.ofDays(1)), null);
        normalizer.normalize();
        var after = reload(p);
        assertEquals(PaymentStatus.OVERDUE, after.getStatus());
        assertNull(after.getConfirmationDueAt());
    }

    @Test void refundWithProofGoesBackToReportedAndUnlocksTheDepositRecord() {
        var p = disputed(PaymentType.DEPOSIT_REFUND, now.plus(Duration.ofDays(1)), null);
        evidence.saveAndFlush(PaymentEvidence.builder().paymentRequestId(p.getId()).uploadedBy("payer").storageObjectId("proof-1").build());
        var deposit = deposits.saveAndFlush(DepositRecord.builder().rentalRequestId("rental-2").initialPaymentRequestId("initial-2")
            .landlordId("payee").tenantId("payer").originalAmount(new BigDecimal("4400000")).heldAmount(new BigDecimal("4400000"))
            .refundableAmount(new BigDecimal("4400000")).status(DepositStatus.DISPUTED).refundPaymentRequestId(p.getId()).build());

        normalizer.normalize();

        assertEquals(PaymentStatus.TRANSFER_REPORTED, reload(p).getStatus());
        assertEquals(DepositStatus.REFUND_REPORTED, deposits.findById(deposit.getId()).orElseThrow().getStatus());
    }

    @Test void onboardingPaymentOfAClosedRequestIsCancelledNotReopened() {
        when(rentals.findById("rental-gone")).thenReturn(Optional.empty());
        var cancelledRental = mock(RentalRequest.class);
        when(cancelledRental.getStatus()).thenReturn(RentalRequestStatus.CANCELLED_BY_RENTER);
        when(rentals.findById("rental-cancelled")).thenReturn(Optional.of(cancelledRental));
        var missing = disputed(PaymentType.RESERVATION_DEPOSIT, now.minus(Duration.ofHours(2)), "rental-gone");
        var cancelled = disputed(PaymentType.INITIAL, now.minus(Duration.ofHours(2)), "rental-cancelled");

        normalizer.normalize();

        for (var p : new PaymentRequest[]{missing, cancelled}) {
            var after = reload(p);
            assertEquals(PaymentStatus.CANCELLED, after.getStatus());
            assertEquals(now, after.getCancelledAt());
        }
        verify(cancelledRental, never()).setHoldExpiresAt(any());
        verify(rentals, never()).save(any());
    }

    @Test void onboardingPaymentOfAnActiveHoldGetsAFreshTransferWindow() {
        var rental = mock(RentalRequest.class);
        when(rental.getStatus()).thenReturn(RentalRequestStatus.ACCEPTED);
        when(rentals.findById("rental-live")).thenReturn(Optional.of(rental));
        var p = disputed(PaymentType.RESERVATION_DEPOSIT, now.minus(Duration.ofHours(2)), "rental-live");

        normalizer.normalize();

        var after = reload(p);
        assertEquals(PaymentStatus.AWAITING_TRANSFER, after.getStatus());
        assertEquals(now.plus(Duration.ofHours(24)), after.getDueAt());
        verify(rental).setHoldExpiresAt(now.plus(Duration.ofHours(24)));
        verify(rentals).save(rental);
    }
}
