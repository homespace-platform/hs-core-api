package com.hs.contract.service;

import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.listing.membership.MembershipService;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.receipt.PaymentReceipt;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HS_TEST_PG_URL",matches="jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1)(?::[0-9]+)?/hs_branch_attention_test_[A-Za-z0-9_]+")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(classes=BranchOverviewPostgresTest.Config.class)
@TestPropertySource(properties={"spring.datasource.url=${HS_TEST_PG_URL}","spring.datasource.username=${HS_TEST_PG_USER}",
        "spring.datasource.password=${HS_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true","spring.jpa.open-in-view=false",
        "spring.flyway.enabled=false","spring.liquibase.enabled=false"})
@Import({LandlordDashboardService.class,LandlordDashboardDetailsRepository.class,BranchAttentionService.class,BranchOverviewService.class})
class LandlordDashboardPostgresTest {
    static final String A="p1-owner-a",B="p1-owner-b";
    static final Instant NOW=Instant.parse("2026-11-30T17:00:00Z");
    static final Instant FROM=Instant.parse("2026-10-31T17:00:00Z");
    static final LocalDate TODAY=LocalDate.of(2026,12,1);
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired LandlordDashboardReadRepository reads;
    @Autowired LandlordDashboardDetailsRepository details;
    @Autowired LandlordDashboardService service;
    @Autowired BranchAttentionService attention;
    @MockitoBean MembershipService membership;
    @MockitoBean RentalOnboardingService onboarding;

    @Test
    void receiptsUseHalfOpenVietnamMonthsExactMoneyAndDoNotCrossOwnersOrCountDeposits() {
        var initial=payment(A,PaymentType.INITIAL,PaymentStatus.CONFIRMED,"130.25",FROM);
        receipt(initial,A,FROM);
        line(initial,"DEPOSIT","100.00");
        var monthly=payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"70.50",NOW.minusSeconds(1));
        monthly.setDueAt(FROM.minusSeconds(1));
        receipt(monthly,A,NOW.minusSeconds(1));
        receipt(payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"999",NOW),A,NOW);
        receipt(payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"999",FROM.minusSeconds(1)),A,FROM.minusSeconds(1));
        receipt(payment(A,PaymentType.RESERVATION_DEPOSIT,PaymentStatus.CONFIRMED,"20",FROM),A,FROM);
        var refund=payment(A,PaymentType.INITIAL_PAYMENT_REFUND,PaymentStatus.CONFIRMED,"80",FROM);
        receipt(refund,A,FROM); line(refund,"DEPOSIT_REFUND","50");
        receipt(payment(A,PaymentType.DEPOSIT_REFUND,PaymentStatus.CONFIRMED,"900",FROM),A,FROM);
        receipt(payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CANCELLED,"999",FROM),A,FROM);
        receipt(payment(B,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"1000",FROM),B,FROM);
        // Even a corrupt receipt's landlord must not authorize access to another owner's payment.
        receipt(payment(B,PaymentType.UTILITY,PaymentStatus.CONFIRMED,"1000",FROM),A,FROM);
        payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CANCELLED,"999",FROM);
        payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.EXPIRED,"999",FROM);
        em.flush();

        var money=service.summary(A,YearMonth.of(2026,11),Set.of("money"));
        assertThat(money.errors()).containsEntry("money",false);
        assertThat(money.revenue().receivedVnd()).isEqualByComparingTo("70.75");
        assertThat(money.revenue().refundedVnd()).isEqualByComparingTo("30");
        assertThat(money.revenue().depositReceivedVnd()).isEqualByComparingTo("120");
        assertThat(money.revenue().expectedVnd()).isEqualByComparingTo("30.25");
        assertThat(money.revenue().receiptCount()).isEqualTo(2);
        assertThat(money.revenue().lateFromPreviousVnd()).isEqualByComparingTo("70.50");
        assertThat(service.summary("nobody",YearMonth.of(2026,11),Set.of("money")).revenue().receivedVnd()).isZero();
        assertThat(service.summary(B,YearMonth.of(2026,11),Set.of("money")).revenue().receivedVnd()).isEqualByComparingTo("1000");
    }

    @Test
    void waitingMoneyAndSupplementalMetricsUseActualStatusesAndRealEventClock() {
        payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.TRANSFER_REPORTED,"10",NOW).setConfirmationDueAt(NOW.plusSeconds(10));
        payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.REJECTED,"20",NOW);
        payment(A,PaymentType.DEPOSIT_REFUND,PaymentStatus.OVERDUE,"30",NOW.minusSeconds(1));
        payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CANCELLED,"999",NOW);
        payment(B,PaymentType.MONTHLY_RENT,PaymentStatus.REJECTED,"999",NOW);
        var confirmed=payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"5",NOW);
        var real=Instant.now();
        event(confirmed,"SYSTEM",real.minusSeconds(30));
        event(confirmed,"SYSTEM",real.minusSeconds(20)); // count a payment once, even with duplicate legacy events
        event(payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"5",NOW),"SYSTEM",real.minusSeconds(31*86400));
        event(payment(B,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"5",NOW),"SYSTEM",real);
        var root=contract(A,rental(A,listing(A,ListingStatus.PUBLISHED),RentalRequestStatus.COMPLETED),ContractStatus.ACTIVE);
        var old=invoice(root,0,MonthlyInvoiceStatus.ROLLED_OVER,"25",FROM);
        var target=invoice(root,1,MonthlyInvoiceStatus.UNPAID,"40",FROM);
        target.setLateFeeAmount(new BigDecimal("2.50"));old.setRolledToInvoiceId(target.getId());
        invoice(root,2,MonthlyInvoiceStatus.CANCELLED,"999",FROM).setLateFeeAmount(new BigDecimal("999"));
        receipt(confirmed,A,real.minusSeconds(600)).setPdfStatus(PaymentReceipt.PdfStatus.FAILED);
        var pending=receipt(payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"5",NOW),A,NOW);
        pending.setPdfStatus(PaymentReceipt.PdfStatus.PENDING);
        em.flush();
        jdbc.update("update payment_receipts set created_at=? where id=?",java.sql.Timestamp.from(real.minusSeconds(600)),pending.getId());
        var m=service.summary(A,YearMonth.of(2026,11),Set.of("money")).revenue();
        assertThat(m).isNotNull();
        assertThat(m.awaitingConfirm().count()).isEqualTo(1);
        assertThat(m.awaitingConfirm().vnd()).isEqualByComparingTo("10");
        assertThat(m.rejected().vnd()).isEqualByComparingTo("20");
        assertThat(m.refundsOwed().vnd()).isEqualByComparingTo("30");
        assertThat(m.autoConfirmed30d()).isEqualTo(1);
        assertThat(m.lateFeeVnd()).isEqualByComparingTo("2.50");
        assertThat(m.rolledOverVnd()).isEqualByComparingTo("25");
        assertThat(m.receiptPdfIssues()).isEqualTo(2);
        target.setStatus(MonthlyInvoiceStatus.PAID);em.flush();
        assertThat(details.money(A,NOW,real,FROM,NOW).rolled()).isZero();
    }

    @Test
    void nativeListsAreBoundedScopedAndSafeAndEffectiveListingsExpireCorrectly() {
        var real=Instant.now();
        var live=listing(A,ListingStatus.PENDING_REVIEW);live.setReviewOperationalStatus(ListingStatus.PUBLISHED);
        live.setExpiresAt(real.plusSeconds(86400));live.setPublishedAt(real.minusSeconds(8*86400));
        var expired=listing(A,ListingStatus.PUBLISHED);expired.setExpiresAt(real.minusSeconds(1));
        for(int i=0;i<7;i++) {var l=listing(A,ListingStatus.REJECTED);l.setStatusReason("x".repeat(150));}
        listing(B,ListingStatus.VIOLATION);
        em.persist(ListingFavorite.builder().id(id()).listing(live).userId("favourite-user").build());
        for(int i=0;i<3;i++) rental(A,live,RentalRequestStatus.PENDING).setRespondBy(NOW.plusSeconds(10+i));
        rental(A,live,RentalRequestStatus.PENDING).setRespondBy(NOW); // expires exactly now
        rental(B,live,RentalRequestStatus.PENDING).setRespondBy(NOW.plusSeconds(30)); // corrupt cross-owner reference
        for(int i=0;i<12;i++) rental(A,live,RentalRequestStatus.ACCEPTED).setHoldExpiresAt(NOW.plusSeconds(60+i));
        rental(A,live,RentalRequestStatus.CANCELLED_BY_SYSTEM).setHoldExpiresAt(NOW.plusSeconds(60));
        em.flush();
        assertThat(details.needsFix(A).rejected()).isEqualTo(7);
        assertThat(details.needsFix(A).items()).hasSize(5).allSatisfy(i->assertThat(i.reason()).hasSize(120));
        assertThat(details.needsFix(B).items()).hasSize(1);
        assertThat(details.unplaced(A).listings()).isEqualTo(9);
        assertThat(details.competing(A,NOW)).singleElement().satisfies(c->assertThat(c.pendingCount()).isEqualTo(3));
        assertThat(details.competing(B,NOW)).isEmpty();
        assertThat(details.holds(A,NOW)).hasSize(10);
        assertThat(details.listingSignals(A,List.of(live.getId()),real).get(live.getId()).favorites()).isEqualTo(1);
        assertThat(details.listingSignals(B,List.of(live.getId()),real)).isEmpty();
        assertThat(reads.expiringListings(A,real,real.plusSeconds(7*86400),PageRequest.of(0,5)))
                .singleElement().satisfies(l->assertThat(l.id()).isEqualTo(live.getId()));
        var summary=service.summary(A,null,Set.of("rooms"));
        assertThat(summary.errors()).containsEntry("rooms",false);
        assertThat(summary.listings().published()).isEqualTo(1);
    }

    @Test
    void visitsSortAndLimitByProposedTimeIncludingOldOriginalDatesAndExcludeCancelledAndOtherOwners() {
        var l=listing(A,ListingStatus.PUBLISHED);
        var day=LocalDate.of(2026,11,28);
        appointment(A,l,day,LocalTime.of(9,0),AppointmentStatus.CANCELLED);
        appointment(A,l,day,LocalTime.of(9,0),AppointmentStatus.PENDING);
        appointment(B,l,day,LocalTime.of(11,0),AppointmentStatus.CONFIRMED);
        var moved=appointment(A,l,day.minusDays(5),LocalTime.NOON,AppointmentStatus.CONFIRMED);
        moved.setRescheduleRequested(true);moved.setProposedDate(day);moved.setProposedStartTime(LocalTime.of(10,0));
        for(int i=0;i<8;i++) appointment(A,l,day.plusDays(i+1),LocalTime.NOON,AppointmentStatus.CONFIRMED);
        em.flush();
        var visits=reads.upcomingVisits(A,day,LocalTime.of(10,0),PageRequest.of(0,5));
        assertThat(visits).hasSize(5);
        assertThat(visits.getFirst().id()).isEqualTo(moved.getId());
        assertThat(reads.upcomingVisits(B,day,LocalTime.of(10,0),PageRequest.of(0,5))).isEmpty();
    }

    @Test
    void closuresSigningComplaintsAndExtraWorkDisappearWithTheirSourceAndPreserveAttentionCounts() {
        var listing=listing(A,ListingStatus.PUBLISHED);
        var r=rental(A,listing,RentalRequestStatus.ACCEPTED);
        r.setDefaultNoticeAt(NOW.minusSeconds(1));r.setDefaultRespondBy(NOW.plusSeconds(60));r.setDefaultNoticeKind("FIRST_RENT");
        payment(A,PaymentType.INITIAL,PaymentStatus.AWAITING_TRANSFER,"10",NOW).setRentalRequestId(r.getId());
        var lease=contract(A,r,ContractStatus.ACTIVE);
        var closure=ContractClosure.builder().contractId(lease.getId()).status("REFUND_PENDING").refundAmount(new BigDecimal("25"))
                .refundDueAt(NOW.minusSeconds(1)).build();em.persist(closure);
        var deposit=contract(A,r,ContractStatus.TENANT_SIGNATURE_PENDING);deposit.setAgreementKind("DEPOSIT");deposit.setTenantSigningDueAt(NOW.plusSeconds(20));
        var signing=contract(A,rental(A,listing,RentalRequestStatus.ACCEPTED),ContractStatus.TENANT_SIGNATURE_PENDING);
        signing.setCurrentRevisionId("current-revision");
        var sig=signature(signing,SignatureRequestStatus.FAILED,"current-revision");
        signature(signing,SignatureRequestStatus.FAILED,"old-revision");
        var other=contract(B,rental(B,listing(B,ListingStatus.PUBLISHED),RentalRequestStatus.ACCEPTED),ContractStatus.TENANT_SIGNATURE_PENDING);
        other.setCurrentRevisionId("other-revision");signature(other,SignatureRequestStatus.FAILED,"other-revision");
        var d=complaint(A,lease,r);complaint(B,other,rental(B,listing(B,ListingStatus.PUBLISHED),RentalRequestStatus.ACCEPTED));
        em.flush();
        assertThat(details.signatureIssues(A).failed()).isEqualTo(1);
        assertThat(details.depositAgreements(A).waitingSign()).isEqualTo(1);
        assertThat(details.closures(A,TODAY).refundVnd()).isEqualByComparingTo("25");
        assertThat(details.complaints(A).openCount()).isEqualTo(1);
        assertThat(details.complaints(A).items()).singleElement().satisfies(c->assertThat(c.responseDueFor()).isEqualTo("YOU"));
        var before=attention.summary(A).branches();
        var extra=details.extraWork(A,NOW,Instant.now(),TODAY);
        assertThat(extra.groups()).containsEntry("DEFAULT_NOTICE",1).containsEntry("COMPLAINT_RESPOND",1)
                .containsEntry("CLOSURE_ACTION",1).containsEntry("SIGNATURE_FAILED",1).containsEntry("DEPOSIT_SIGN",1);
        assertThat(extra.items()).filteredOn(i->i.type().equals("SIGNATURE_FAILED"))
                .allSatisfy(i->assertThat(i.clock()).isEqualTo(com.hs.contract.dto.branch.BranchOverviewResponse.Clock.REAL));
        service.summary(A,null,Set.of("todo","contracts","complaints"));
        assertThat(attention.summary(A).branches()).isEqualTo(before);
        d.setStatus(DisputeCaseStatus.RESOLVED);closure.setStatus("CLOSED");r.setDefaultCompletedAt(NOW);
        sig.setStatus(SignatureRequestStatus.SIGNED);deposit.setStatus(ContractStatus.CANCELLED);em.flush();
        assertThat(details.extraWork(A,NOW,Instant.now(),TODAY).groups()).isEmpty();
        assertThat(details.complaints(A).openCount()).isZero();
    }

    @Test
    void endedExclusiveDateHonoursEarlyEndsAndEffectiveMonthsAndExpiryBandsMatchTheSharedCalendar() {
        var branch=PropertyBranch.builder().id(id()).ownerId(A).name("P1").category(ListingCategory.ROOM).build();em.persist(branch);
        for(int days:new int[]{0,1,30,31,60,61}) {
            var r=rental(A,listing(A,ListingStatus.PUBLISHED),RentalRequestStatus.COMPLETED);
            r.setMoveInDate(TODAY.minusMonths(1));r.setLeaseMonths(12);
            var c=contract(A,r,ContractStatus.ACTIVE);c.setAgreedEarlyEndExclusive(TODAY.plusDays(days));
            em.persist(new BranchContractAssignment(c.getId(),branch.getId(),null));
        }
        var rental=rental(A,listing(A,ListingStatus.PUBLISHED),RentalRequestStatus.COMPLETED);
        rental.setMoveInDate(LocalDate.of(2026,10,31));rental.setLeaseMonths(12);
        var effective=contract(A,rental,ContractStatus.EXPIRED);effective.setEffectiveLeaseMonths(1);
        em.persist(new BranchContractAssignment(effective.getId(),branch.getId(),null));
        em.flush();
        assertThat(details.closures(A,TODAY).endedNotClosed()).isEqualTo(2);
        var expiries=attention.overview(A).expiring();
        assertThat(expiries).extracting(BranchAttentionService.ExpiringLease::daysRemaining).containsExactly(1,30,31,60);
        assertThat(expiries).extracting(e->e.band().name()).containsExactly("WITHIN_30","WITHIN_30","WITHIN_60","WITHIN_60");
        assertThat(details.closures(B,TODAY).endedNotClosed()).isZero();
    }

    @Test
    void membershipOrdersExcludeExpiredPendingAndUseRealMonthBoundariesWithoutRevenueLeakage() {
        order(A,MembershipOrder.Status.PENDING,NOW.plusSeconds(1),null,100);
        order(A,MembershipOrder.Status.PENDING,NOW,null,999);
        order(A,MembershipOrder.Status.FAILED,NOW.plusSeconds(1),null,999);
        order(A,MembershipOrder.Status.PAID,NOW,FROM,125);
        order(A,MembershipOrder.Status.PAID,NOW,NOW.minusSeconds(1),225);
        order(A,MembershipOrder.Status.PAID,NOW,NOW,999);
        order(B,MembershipOrder.Status.PAID,NOW,FROM,999);
        em.flush();
        var orders=details.orders(A,NOW,FROM,NOW);
        assertThat(orders.pending()).isEqualTo(1);
        assertThat(orders.failed()).isEqualTo(1);
        assertThat(orders.paid()).isEqualByComparingTo("350");
        assertThat(reads.receiptTotals(A,FROM,NOW)).isEmpty();
    }

    @Test
    void overdueTotalsUseTheSameManagedWorkAndExcludeCancelledPayments() {
        var branch=PropertyBranch.builder().id(id()).ownerId(A).name("P1").category(ListingCategory.ROOM).build();em.persist(branch);
        var rental=rental(A,listing(A,ListingStatus.RESERVED),RentalRequestStatus.COMPLETED);
        var root=contract(A,rental,ContractStatus.ACTIVE);
        em.persist(new BranchContractAssignment(root.getId(),branch.getId(),null));
        var invoice=invoice(root,0,MonthlyInvoiceStatus.OVERDUE,"51.25",NOW.minusSeconds(1));invoice.setIssuedAt(FROM);
        var p=payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.OVERDUE,"51.25",NOW.minusSeconds(1));
        p.setContractId(root.getId());p.setInvoiceId(invoice.getId());invoice.setPaymentRequestId(p.getId());
        payment(A,PaymentType.MONTHLY_RENT,PaymentStatus.CANCELLED,"999",NOW.minusSeconds(1)).setContractId(root.getId());
        em.flush();
        var count=attention.overview(A).allWork().stream().filter(w->w.type()==com.hs.contract.dto.branch.BranchAttentionResponse.WorkType.PAYMENT_OVERDUE).count();
        var money=service.summary(A,YearMonth.of(2026,11),Set.of("money")).revenue();
        assertThat(count).isEqualTo(1);
        assertThat(money.overdueCount()).isEqualTo(count);
        assertThat(money.overdueVnd()).isEqualByComparingTo("51.25");
        assertThat(details.overdueAmount(B,List.of(p.getId(),invoice.getId()))).isZero();
    }

    @Test
    void aggregationStaysBoundedWithOneHundredThousandPaymentsAndTenThousandListings() {
        em.flush();
        jdbc.execute("SET LOCAL statement_timeout='10s'");
        jdbc.update("""
                insert into payment_requests(id,payer_id,payee_id,type,direction,status,currency,total_amount,transfer_reference,active,due_at,version)
                select 'load-p-'||n,'tenant',case when n<=10 then ? else ? end,'MONTHLY_RENT','TENANT_TO_LANDLORD',
                  'AWAITING_TRANSFER','VND',1.25,'load-ref-'||n,true,?,0 from generate_series(1,100000) n
                """,A,B,java.sql.Timestamp.from(FROM));
        jdbc.update("""
                insert into listings(id,owner_id,title,description,category,status,active,available_from,area_m2,price_amount,
                  currency,price_unit,deposit_type,payment_cycle,minimum_lease_months,negotiable,management_fee_included,version,view_count)
                select 'load-l-'||n,?,'Listing','Test','ROOM','REJECTED',true,date '2026-11-01',10,10,
                  'VND','MONTH','NONE','MONTHLY',1,false,false,0,0 from generate_series(1,10000) n
                """,A);
        var start=System.nanoTime();
        assertThat(reads.expectedTotals(A,Set.of(PaymentType.MONTHLY_RENT),FROM,NOW)).singleElement()
                .satisfies(r->assertThat((BigDecimal)r[1]).isEqualByComparingTo("12.50"));
        assertThat(details.needsFix(A).items()).hasSize(5);
        assertThat(details.needsFix(A).rejected()).isEqualTo(10000);
        assertThat(details.extraWork(A,NOW,Instant.now(),TODAY).items()).hasSizeLessThanOrEqualTo(10);
        System.out.printf("P1 aggregation fixture: 100000 payments, 10000 listings; queried in %d ms%n",(System.nanoTime()-start)/1_000_000);
    }

    @Test
    void branchScopeQueryCountDoesNotGrowFromThreeToThirtyBranches() {
        var statistics=em.getEntityManagerFactory().unwrap(org.hibernate.SessionFactory.class).getStatistics();
        for(int i=0;i<3;i++) save(PropertyBranch.builder().id(id()).ownerId(A).name("P1").category(ListingCategory.ROOM).build());
        em.flush();em.clear();statistics.clear();
        var first=service.summary(A,null,Set.of("rooms","todo","contracts","money"));
        long small=statistics.getPrepareStatementCount();
        assertThat(first.errors()).doesNotContainValue(true);
        for(int i=3;i<30;i++) save(PropertyBranch.builder().id(id()).ownerId(A).name("P1").category(ListingCategory.ROOM).build());
        em.flush();em.clear();statistics.clear();
        var second=service.summary(A,null,Set.of("rooms","todo","contracts","money"));
        assertThat(second.errors()).doesNotContainValue(true);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(small);
        System.out.printf("P1 branch scope: 3 and 30 branches both use %d Hibernate statements; JDBC projections execute a fixed set of aggregate queries%n",small);
    }

    private static String id(){return UUID.randomUUID().toString();}
    private <T> T save(T entity){em.persist(entity);return entity;}
    private Listing listing(String owner,ListingStatus status){return save(Listing.builder().ownerId(owner).title("Listing")
            .description("Test").category(ListingCategory.ROOM).status(status).priceAmount(BigDecimal.TEN).areaM2(BigDecimal.TEN)
            .currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY).availableFrom(TODAY)
            .minimumLeaseMonths(1).depositType(DepositType.NONE).build());}
    private RentalRequest rental(String owner,Listing listing,RentalRequestStatus status){return save(RentalRequest.builder().id(id())
            .listing(listing).ownerId(owner).renterId("tenant").renterName("Tenant").renterPhone("0900000000").status(status)
            .moveInDate(TODAY.minusMonths(1)).leaseMonths(3).occupantCount(1).motorbikeCount(0).carCount(0)
            .monthlyRentPrice(BigDecimal.TEN).depositAmount(BigDecimal.ZERO).build());}
    private Contract contract(String owner,RentalRequest r,ContractStatus status){return save(Contract.builder().contractNumber("P1-"+id())
            .rentalRequestId(r.getId()).listingId(r.getListing().getId()).landlordId(owner).tenantId(r.getRenterId())
            .templateId("template").templateVersionId("version").status(status).build());}
    private PaymentRequest payment(String owner,PaymentType type,PaymentStatus status,String amount,Instant due){
        boolean refund=type==PaymentType.INITIAL_PAYMENT_REFUND||type==PaymentType.DEPOSIT_REFUND;
        return save(PaymentRequest.builder().payerId(refund?owner:"tenant").payeeId(refund?"tenant":owner)
                .direction(refund?PaymentDirection.LANDLORD_TO_TENANT:PaymentDirection.TENANT_TO_LANDLORD).type(type).status(status)
                .totalAmount(new BigDecimal(amount)).dueAt(due).transferReference(id().substring(0,18)).build());}
    private PaymentReceipt receipt(PaymentRequest p,String owner,Instant at){var r=new PaymentReceipt();r.setId(id());r.setPaymentRequestId(p.getId());
        r.setReceiptNumber(id());r.setLandlordId(owner);r.setTenantId("tenant");r.setIssuedAt(at);r.setSnapshotJson("{}");
        r.setSnapshotHash("0".repeat(64));r.setPdfStatus(PaymentReceipt.PdfStatus.READY);return save(r);}
    private void line(PaymentRequest p,String type,String amount){save(PaymentLineItem.builder().paymentRequestId(p.getId()).type(type)
            .displayName(type).amount(new BigDecimal(amount)).build());}
    private void event(PaymentRequest p,String actor,Instant at){save(PaymentEvent.builder().paymentRequestId(p.getId()).eventType(PaymentEventType.RECEIPT_CONFIRMED)
            .actorId(actor).createdAt(at).build());}
    private MonthlyInvoice invoice(Contract c,int period,MonthlyInvoiceStatus status,String amount,Instant due){return save(MonthlyInvoice.builder()
            .contractId(c.getId()).periodIndex(period).periodStart(TODAY.minusMonths(1)).periodEndExclusive(TODAY).status(status)
            .totalAmount(new BigDecimal(amount)).dueAt(due).build());}
    private ViewingAppointment appointment(String owner,Listing l,LocalDate date,LocalTime time,AppointmentStatus status){return save(ViewingAppointment.builder()
            .id(id()).listing(l).ownerId(owner).renterId("tenant").renterName("Tenant").renterPhone("0900000000")
            .appointmentDate(date).startTime(time).endTime(time.plusHours(1)).status(status).build());}
    private SignatureRequest signature(Contract c,SignatureRequestStatus status,String revision){return save(SignatureRequest.builder().contractId(c.getId())
            .revisionId(revision).sourceDocumentId("doc").signerUserId(A).signerRole(SignerRole.LANDLORD).status(status).docId(id())
            .expiresAt(Instant.now().plusSeconds(20)).build());}
    private DisputeCase complaint(String owner,Contract c,RentalRequest rental){return save(DisputeCase.builder().subjectType("RENTAL").subjectId(rental.getId())
            .rentalRequestId(rental.getId()).contractId(c.getId()).payerId("tenant").payeeId(owner).openedBy("tenant").reason("PRIVATE REASON")
            .openedAt(NOW).status(DisputeCaseStatus.OPEN).amountSnapshot(BigDecimal.TEN).currency("VND").initialEvidenceSnapshot("{}")
            .complaintCategory("OTHER").complaintStage("WAITING_RESPONSE").respondBy(NOW.plusSeconds(60)).responseDueFor(owner).build());}
    private void order(String owner,MembershipOrder.Status status,Instant expires,Instant paid,int amount){var o=new MembershipOrder();o.setId(id().replace("-",""));
        o.setOwnerId(owner);o.setStatus(status);o.setAmountVnd(amount);o.setCreatedAt(NOW.minusSeconds(60));o.setExpiresAt(expires);o.setPaidAt(paid);save(o);}
}
