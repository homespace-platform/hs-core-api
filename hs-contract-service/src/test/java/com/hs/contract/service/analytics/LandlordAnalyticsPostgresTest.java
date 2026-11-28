package com.hs.contract.service.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.analytics.LandlordAnalyticsQuery;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.config.AnalyticsResponseSchemaMigration;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.receipt.*;
import com.hs.payment.repository.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="HS_P3_TEST_PG_URL", matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_analytics_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=LandlordAnalyticsPostgresTest.Config.class)
@ActiveProfiles("test")
@TestPropertySource(properties={"spring.datasource.url=${HS_P3_TEST_PG_URL}", "spring.datasource.username=postgres",
        "spring.datasource.password=p3_disposable", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true", "payment.receipts.backfill-enabled=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({LandlordAnalyticsQueries.class, LandlordAnalyticsService.class, LandlordAnalyticsExportService.class,
        PaymentReceiptService.class, PaymentReceiptBackfill.class, AnalyticsResponseSchemaMigration.class, ListingViewService.class})
class LandlordAnalyticsPostgresTest {
    static final Instant NOW = Instant.parse("2026-11-30T17:00:00Z");
    static final Instant START = Instant.parse("2026-09-30T17:00:00Z");
    static final Instant END = Instant.parse("2026-11-30T17:00:00Z");
    @SpringBootConfiguration @EntityScan("com.hs") @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses={ContractRepository.class, RentalRequestRepository.class,
            PaymentRequestRepository.class, PaymentReceiptRepository.class, StorageObjectRepository.class, UserRepository.class})
    static class Config {
        @Bean BillingTime time(Environment env) { return new BillingTime(NOW.toString(), env); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean StorageService storage() { return mock(StorageService.class); }
        @Bean PaymentReceiptPdfRenderer renderer() { return new PaymentReceiptPdfRenderer("http://127.0.0.1:1", false); }
        @Bean org.springframework.data.redis.core.StringRedisTemplate redis() {
            var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
            org.springframework.data.redis.core.ValueOperations<String,String> ops=mock(org.springframework.data.redis.core.ValueOperations.class);
            when(redis.opsForValue()).thenReturn(ops);
            when(ops.setIfAbsent(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(Duration.class))).thenReturn(true);
            return redis;
        }
        @Bean ListingViewHistoryService history() { return mock(ListingViewHistoryService.class); }
    }
    @Autowired LandlordAnalyticsQueries queries;
    @Autowired LandlordAnalyticsService analytics;
    @Autowired LandlordAnalyticsExportService exports;
    @Autowired PropertyBranchRepository branches;
    @Autowired ListingRepository listings;
    @Autowired RentalRequestRepository rentals;
    @Autowired ContractRepository contracts;
    @Autowired PaymentRequestRepository payments;
    @Autowired PaymentLineItemRepository lines;
    @Autowired PaymentReceiptRepository receipts;
    @Autowired PaymentReceiptService receiptService;
    @Autowired PaymentReceiptBackfill backfill;
    @Autowired AnalyticsResponseSchemaMigration migration;
    @Autowired ListingViewService views;
    @Autowired ListingViewDailyRepository daily;
    @Autowired BranchFloorRepository floors;
    @Autowired BranchRowRepository rows;
    @Autowired BranchSlotRepository slots;
    @Autowired BranchRoomListingRepository roomLinks;
    @Autowired BranchContractAssignmentRepository assignments;
    @Autowired ContractClosureRepository closures;
    @Autowired MonthlyInvoiceRepository invoices;
    @Autowired ViewingAppointmentRepository appointments;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    String owner, other;
    PropertyBranch branch;
    Listing listing;
    @BeforeEach void setup() {
        owner=id(); other=id();
        branch=branches.saveAndFlush(PropertyBranch.builder().id(id()).ownerId(owner).name("=Branch").category(ListingCategory.ROOM).totalUnits(2).build());
        listing=listing(owner, branch.getId());
    }
    LandlordAnalyticsQueries.Scope scope() { return new LandlordAnalyticsQueries.Scope(owner,branch.getId(),listing.getId()); }
    LandlordAnalyticsQuery query(String section) { return new LandlordAnalyticsQuery(YearMonth.of(2026,10), YearMonth.of(2026,11),branch.getId(),listing.getId(),Set.of(section),1,10,false); }
    static String id() { return UUID.randomUUID().toString(); }
    static BigDecimal bd(String v) { return new BigDecimal(v); }
    static void money(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual)); }
    void tx(Runnable work) { new TransactionTemplate(transactions).executeWithoutResult(t->work.run()); }
    Listing listing(String user,String branchId) {
        var l=Listing.builder().id(id()).ownerId(user).branchId(branchId).title("=cmd, \"room\"").description("Test")
                .category(ListingCategory.ROOM).status(ListingStatus.PUBLISHED).publishedAt(NOW.minusSeconds(7*86400))
                .priceAmount(bd("100")).areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH)
                .paymentCycle(PaymentCycle.MONTHLY).availableFrom(LocalDate.of(2026,10,1)).minimumLeaseMonths(1).depositType(DepositType.NONE).build();
        return listings.saveAndFlush(l);
    }
    PaymentRequest payment(String user, Listing l, PaymentType type, PaymentStatus status,String amount,Instant due,Instant confirmed) {
        boolean refund=type==PaymentType.INITIAL_PAYMENT_REFUND;
        var p=PaymentRequest.builder().listingId(l.getId()).payerId(refund?user:"tenant").payeeId(refund?"tenant":user)
                .type(type).direction(refund?PaymentDirection.LANDLORD_TO_TENANT:PaymentDirection.TENANT_TO_LANDLORD)
                .status(status).totalAmount(bd(amount)).dueAt(due).confirmedAt(confirmed).transferReference(id().substring(0,18)).build();
        return payments.saveAndFlush(p);
    }
    void line(PaymentRequest p,String type,String amount) {
        lines.saveAndFlush(PaymentLineItem.builder().paymentRequestId(p.getId()).type(type).displayName(type).amount(bd(amount)).build());
    }
    void receipt(PaymentRequest p) { tx(()->receiptService.capture(payments.findByIdForUpdate(p.getId()).orElseThrow())); }

    @Test void receiptsExcludeDepositsAndRefundDepositsAtVietnamMonthBoundaryAndIsolateOwners() {
        Instant oct=Instant.parse("2026-10-31T16:59:59.999Z"), nov=oct.plusMillis(1);
        var initial=payment(owner,listing,PaymentType.INITIAL,PaymentStatus.CONFIRMED,"1000.25",oct,oct);
        line(initial,"DEPOSIT","900"); line(initial,"RENT","100.25"); receipt(initial);
        var refund=payment(owner,listing,PaymentType.INITIAL_PAYMENT_REFUND,PaymentStatus.CONFIRMED,"100.25",nov,nov);
        line(refund,"DEPOSIT_REFUND","40");line(refund,"RENT_REFUND","60.25");receipt(refund);
        receipt(payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"50.50",oct,nov));
        receipt(payment(other,listing(other,null),PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"999999",nov,nov));
        receipt(payment(owner,listing,PaymentType.RESERVATION_DEPOSIT,PaymentStatus.CONFIRMED,"999",nov,nov));
        payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.CANCELLED,"99999",nov,nov);
        payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.EXPIRED,"99999",nov,nov);
        var rows=analytics.analytics(owner,query("revenue")).revenue();
        money("100.25",rows.getFirst().receivedVnd()); money("50.50",rows.getLast().receivedVnd());
        money("60.25",rows.getLast().refundedVnd());money("-9.75",rows.getLast().netVnd());
        money("50.50",rows.getLast().lateCollectedVnd());money("150.75",rows.getFirst().dueVnd());
        money("0",rows.getLast().dueVnd());
        assertEquals(LocalDate.of(2026,10,31),queries.receiptsSince(owner));
        assertTrue(queries.receipts(new LandlordAnalyticsQueries.Scope(other,branch.getId(),null),START,END).isEmpty());
        var csv=new String(exports.export(owner,query("revenue"),"revenue","csv","receipt").bytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"));assertTrue(csv.contains("'="));assertTrue(csv.contains("-60.25"));assertFalse(csv.contains("999999"));
        assertEquals(3,queries.receiptExport(scope(),START,END,20001).size());
    }

    @Test void backfillRetriesConcurrentWorkersAndSkipsUnpaidCancelledAndMissingConfirmation() throws Exception {
        var paid=payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"123.45",START,START);
        for(var status:List.of(PaymentStatus.CANCELLED,PaymentStatus.TRANSFER_REPORTED,PaymentStatus.AWAITING_TRANSFER,PaymentStatus.EXPIRED))
            payment(owner,listing,PaymentType.MONTHLY_RENT,status,"123",START,START);
        var unknown=payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.CONFIRMED,"123",START,null);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(backfill::run);var b=pool.submit(backfill::run);
            assertEquals(0,a.get(30,TimeUnit.SECONDS).failed());assertEquals(0,b.get(30,TimeUnit.SECONDS).failed());
        } finally { pool.shutdownNow(); }
        var r=receipts.findByPaymentRequestId(paid.getId()).orElseThrow();
        assertEquals(START,r.getIssuedAt());String hash=r.getSnapshotHash();
        assertEquals(0,backfill.run().created());assertEquals(hash,receipts.findByPaymentRequestId(paid.getId()).orElseThrow().getSnapshotHash());
        assertTrue(receipts.findByPaymentRequestId(unknown.getId()).isEmpty());
        assertEquals(1,jdbc.queryForObject("select count(*) from payment_receipts where landlord_id=?",Integer.class,owner));
    }

    @Test void debtBoundaryDaysCancelledExclusionAndOtherBranchIsolation() {
        for(int days:List.of(0,7,8,30,31)) payment(owner,listing,PaymentType.MONTHLY_RENT,
                days==8?PaymentStatus.TRANSFER_REPORTED:PaymentStatus.OVERDUE,"100.25",days==0?NOW.minusSeconds(1):NOW.minusSeconds(days*86400L-1),null);
        payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.CANCELLED,"9999",START,null);
        payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.AWAITING_TRANSFER,"9999",NOW,null);
        payment(other,listing(other,null),PaymentType.MONTHLY_RENT,PaymentStatus.OVERDUE,"9999",START,null);
        var d=analytics.analytics(owner,query("receivables")).receivables();
        // Due at one second before midnight belongs to the previous Vietnamese calendar date.
        assertEquals(List.of(2,2,1),d.buckets().stream().map(x->x.count()).toList());
        money("501.25",d.totalVnd()); assertEquals(5,d.top().size());
        assertEquals(5,queries.debtExport(scope(),NOW,LocalDate.of(2026,12,1),20001).size());
        assertTrue(queries.debtDays(new LandlordAnalyticsQueries.Scope(other,branch.getId(),null),NOW,LocalDate.of(2026,12,1)).isEmpty());
        var foreign=rental(RentalRequestStatus.PENDING,START);foreign.setOwnerId(other);foreign.setRenterName("SECRET OTHER OWNER");rentals.saveAndFlush(foreign);
        var linked=payment(owner,listing,PaymentType.MONTHLY_RENT,PaymentStatus.OVERDUE,"1",START,null);
        linked.setRentalRequestId(foreign.getId());payments.saveAndFlush(linked);
        assertNull(queries.topDebts(scope(),NOW,10).stream().filter(r->r.paymentId().equals(linked.getId())).findFirst().orElseThrow().tenantName());
    }

    @Test void everyQueryUsesRealSchemaWithEmptyAndForeignScopes() {
        var result=analytics.analytics(owner,new LandlordAnalyticsQuery(null,null,branch.getId(),listing.getId(),Set.of(),1,10,false));
        assertEquals(12,result.revenue().size());assertTrue(result.occupancy().isEmpty());assertEquals(0,result.funnel().contracts());
        assertTrue(queries.listingOwned(owner,listing.getId()));assertFalse(queries.listingOwned(other,listing.getId()));
        assertThrows(com.hs.common.advice.entity.AppException.class,()->analytics.analytics(other,query("revenue")));
        assertNull(queries.viewsSince(new LandlordAnalyticsQueries.Scope(other,null,null)));
        assertEquals(1,queries.countListings(scope(),true,NOW));
        assertEquals(0,queries.countListings(scope(),true,NOW.minusMillis(1)));
        assertTrue(queries.listings(scope(),true,NOW,0,10).getFirst().noLeads());
        assertEquals(0,queries.viewsCumulative(scope()));
        assertEquals(0,queries.viewsDaily(scope(),LocalDate.of(2026,1,1),LocalDate.of(2026,12,31)));
    }

    @Test void dailyAndLifetimeViewsAreAtomicForConcurrentLoggedInAndAnonymousViewsAndRollback() throws Exception {
        // Test profile normally uses Redis throttling; a null Redis bean preserves the established count-every-view fallback.
        var pool=Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<30;i++)tasks.add(pool.submit(()->views.recordView(listing.getId(),"same-viewer",null)));
            for(var task:tasks)task.get(30,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        views.recordView(listing.getId(),null,null); views.recordView(listing.getId(),"second-viewer",null);
        assertEquals(32L,listings.findById(listing.getId()).orElseThrow().getViewCount());
        assertEquals(32,jdbc.queryForObject("select sum(views) from listing_view_daily where listing_id=?",Integer.class,listing.getId()));
        assertEquals(2,jdbc.queryForObject("select sum(unique_logged_in_viewers) from listing_view_daily where listing_id=?",Integer.class,listing.getId()));
        new TransactionTemplate(transactions).executeWithoutResult(t->{ views.recordView(listing.getId(),"rolled-back",null);t.setRollbackOnly(); });
        assertEquals(32L,listings.findById(listing.getId()).orElseThrow().getViewCount());
        assertEquals(2,jdbc.queryForObject("select count(*) from listing_view_daily_viewers where listing_id=?",Integer.class,listing.getId()));
        listing.setStatus(ListingStatus.DRAFT);listings.saveAndFlush(listing);
        assertFalse(views.recordView(listing.getId(),null,null).counted());
    }

    RentalRequest rental(RentalRequestStatus status,Instant at) {
        var r=rentals.saveAndFlush(RentalRequest.builder().id(id()).listing(listing).ownerId(owner).renterId("tenant").renterName("Tenant")
                .renterPhone("0900000000").status(status).moveInDate(LocalDate.of(2026,10,1)).leaseMonths(2).occupantCount(1)
                .monthlyRentPrice(bd("100")).build());
        jdbc.update("update rental_requests set created_at=? where id=?",java.sql.Timestamp.from(at),r.getId());return r;
    }
    Contract contract(RentalRequest r,ContractStatus status) {
        return contracts.saveAndFlush(Contract.builder().contractNumber(id()).rentalRequestId(r.getId()).listingId(listing.getId())
                .landlordId(owner).tenantId("tenant").templateId("template").templateVersionId("version").status(status).signedAt(NOW).build());
    }
    @Test void cohortExcludesCancelledSystemRequestsAndNonLeaseContractsAndCountsFavoritesAndMembershipCosts() {
        var r=rental(RentalRequestStatus.ACCEPTED,START);var c=contract(r,ContractStatus.ACTIVE);
        var cancelled=rental(RentalRequestStatus.CANCELLED_BY_SYSTEM,START);contract(cancelled,ContractStatus.ACTIVE);
        var outside=rental(RentalRequestStatus.ACCEPTED,START.minusMillis(1));contract(outside,ContractStatus.ACTIVE);
        var deposit=contract(r,ContractStatus.ACTIVE);deposit.setAgreementKind("DEPOSIT");contracts.saveAndFlush(deposit);
        var appendix=contract(r,ContractStatus.ACTIVE);appendix.setParentContractId(c.getId());contracts.saveAndFlush(appendix);
        contract(r,ContractStatus.CANCELLED);
        for(var status:List.of(AppointmentStatus.CONFIRMED,AppointmentStatus.CANCELLED)) {
            var a=ViewingAppointment.builder().id(id()).listing(listing).ownerId(owner).renterId("tenant").renterName("Tenant")
                    .renterPhone("0900000000").visitorCount(1).appointmentDate(LocalDate.of(2026,10,2)).startTime(LocalTime.NOON)
                    .endTime(LocalTime.of(13,0)).status(status).build();
            // Use the mapped repository to make Hibernate validate every required field and enum.
            appointments.saveAndFlush(a);jdbc.update("update viewing_appointments set created_at=? where id=?",java.sql.Timestamp.from(START),a.getId());
        }
        jdbc.update("insert into listing_favorites(id,user_id,listing_id,active) values(?,?,?,true)",id(),"tenant",listing.getId());
        var mi=invoices.saveAndFlush(MonthlyInvoice.builder().contractId(c.getId()).periodIndex(1).periodStart(LocalDate.of(2026,10,1))
                .periodEndExclusive(LocalDate.of(2026,11,1)).status(MonthlyInvoiceStatus.ROLLED_OVER).totalAmount(bd("123.45"))
                .lateFeeAmount(bd("2.50")).dueAt(START).deferredAt(Instant.parse("2026-10-31T17:00:00Z")).build());
        jdbc.update("insert into membership_orders(id,owner_id,kind,amount_vnd,upgrade,status,created_at,expires_at,paid_at) values(?,?,'PLAN',999,false,'PAID',?,?,?)",id().replace("-",""),owner,java.sql.Timestamp.from(START),java.sql.Timestamp.from(END),java.sql.Timestamp.from(Instant.parse("2026-10-31T17:00:00Z")));
        var f=analytics.analytics(owner,query("funnel")).funnel();
        assertEquals(1,f.appointments());assertEquals(1,f.requests());assertEquals(1,f.contracts());
        var l=analytics.analytics(owner,query("listings")).listings().items().getFirst();
        assertEquals(1,l.favorites());assertEquals(1,l.contracts());assertFalse(l.noLeads());
        var revenue=analytics.analytics(owner,query("revenue")).revenue();
        money("2.50",revenue.getFirst().lateFeeVnd());money("123.45",revenue.getLast().rolledOverVnd());money("999",revenue.getLast().membershipFeeVnd());
        money("0",revenue.getLast().receivedVnd());
    }

    @Test void occupancyReadsMappedLeaseAndHandoverAndUsesOnlyLinkedRoomsForListing() {
        var floor=new BranchFloor();floor.setBranch(branch);floor.setLabel("Floor");floors.saveAndFlush(floor);
        var row=new BranchRow();row.setFloor(floor);row.setLabel("Row");rows.saveAndFlush(row);
        var slot=new BranchSlot();slot.setBranch(branch);slot.setRow(row);slot.setRoomCode("101");slot.setNormalizedRoomCode("101");slots.saveAndFlush(slot);
        roomLinks.saveAndFlush(new BranchRoomListing(listing.getId(),slot.getId()));
        var root=contract(rental(RentalRequestStatus.ACCEPTED,START),ContractStatus.TERMINATED);
        assignments.saveAndFlush(new BranchContractAssignment(root.getId(),branch.getId(),slot.getId()));
        closures.saveAndFlush(ContractClosure.builder().contractId(root.getId()).status("SETTLED").acceptedAt(Instant.parse("2026-10-20T17:00:00Z")).build());
        var occupancy=analytics.analytics(owner,query("occupancy")).occupancy();
        assertEquals(1,occupancy.getFirst().rooms());assertEquals(20,occupancy.getFirst().occupiedRoomDays());
        assertEquals(31,occupancy.getFirst().roomDays());assertEquals(0,occupancy.getLast().occupiedRoomDays());
        assertThrows(com.hs.common.advice.entity.AppException.class,()->analytics.analytics(other,query("occupancy")));
    }

    @Test void monthlySnapshotIsImmutableAndScopedAfterRoomLayoutChanges() {
        analytics.capturePreviousMonth(owner,branch.getId());
        analytics.capturePreviousMonth(owner,branch.getId());
        assertEquals(1,jdbc.queryForObject("select count(*) from branch_occupancy_monthly where branch_id=?",Integer.class,branch.getId()));
        jdbc.update("update branch_occupancy_monthly set rooms=3, occupied_room_days=30, room_days=90 where branch_id=?",branch.getId());
        analytics.capturePreviousMonth(owner,branch.getId());
        var result=analytics.analytics(owner,new LandlordAnalyticsQuery(YearMonth.of(2026,11),YearMonth.of(2026,11),branch.getId(),null,Set.of("occupancy"),0,0,false));
        assertEquals(3,result.occupancy().getFirst().rooms());assertEquals(30,result.occupancy().getFirst().occupiedRoomDays());
        assertEquals(90,result.occupancy().getFirst().roomDays());
        assertTrue(queries.occupancySnapshots(other,List.of(branch.getId()),LocalDate.of(2026,11,1),LocalDate.of(2026,11,1)).isEmpty());
    }

    @Test void oversizedReceiptExportActuallyQueriesTheBoundedRowsAndReturns422() {
        jdbc.update("insert into payment_requests(id,listing_id,payer_id,payee_id,type,direction,status,currency,total_amount,transfer_reference,active,version) "
                + "select md5(? || g),?,'tenant',?,'MONTHLY_RENT','TENANT_TO_LANDLORD','CONFIRMED','VND',1,left(md5(?),10)||g,true,0 from generate_series(1,20001) g",owner,listing.getId(),owner,owner);
        jdbc.update("insert into payment_receipts(id,payment_request_id,receipt_number,landlord_id,tenant_id,issued_at,snapshot_json,snapshot_hash,pdf_status,attempts,version,active) "
                + "select md5('r'||?||g),md5(?||g),md5('n'||?||g),?,'tenant',?,'{}',repeat('0',64),'PENDING',0,0,true from generate_series(1,20001) g",owner,owner,owner,owner,java.sql.Timestamp.from(START));
        var error=assertThrows(com.hs.common.advice.entity.AppException.class,()->exports.export(owner,query("revenue"),"revenue","csv","receipt"));
        assertEquals(422,error.getStatusCode().value());
    }

    @Test void responseMigrationIsIdempotentAndNeverUsesUpdatedAtAsAnOwnerResponse() {
        var accepted=rental(RentalRequestStatus.ACCEPTED,START);
        var rejected=rental(RentalRequestStatus.REJECTED,START);
        jdbc.update("update rental_requests set accepted_at=? where id=?",java.sql.Timestamp.from(START),accepted.getId());
        jdbc.execute("alter table rental_requests drop column responded_at");
        jdbc.execute("alter table viewing_appointments drop column responded_at");
        migration.migrate();migration.migrate();
        assertNull(jdbc.queryForObject("select responded_at from rental_requests where id=?",java.sql.Timestamp.class,accepted.getId()));
        assertNull(jdbc.queryForObject("select responded_at from rental_requests where id=?",java.sql.Timestamp.class,rejected.getId()));
        assertEquals(2,jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=current_schema() and table_name in ('rental_requests','viewing_appointments') and column_name='responded_at'",Integer.class));
    }
}
