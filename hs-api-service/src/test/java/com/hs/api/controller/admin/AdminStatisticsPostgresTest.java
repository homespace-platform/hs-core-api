package com.hs.api.controller.admin;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.context.*;
import com.hs.common.persistence.BaseEntity;
import com.hs.common.time.BillingTime;
import com.hs.contract.config.AdminStatisticsIndexMigration;
import com.hs.contract.dto.statistics.*;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.notification.OperationalIncident;
import com.hs.contract.service.statistics.*;
import com.hs.listing.membership.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.user.model.*;
import com.hs.user.model.Role;
import com.hs.user.model.constant.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** Hibernate creates the real schema; every report path runs on an isolated PostgreSQL database. */
@EnabledIfEnvironmentVariable(named="HS_P5_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_statistics_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=AdminStatisticsPostgresTest.Config.class)
@TestPropertySource(properties={"spring.datasource.url=${HS_P5_TEST_PG_URL}","spring.datasource.username=postgres",
        "spring.datasource.password=p5_disposable","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"})
@ActiveProfiles("test")
@Import({AdminStatisticsQueries.class,AdminStatisticsService.class,AdminStatisticsController.class,AdminStatisticsIndexMigration.class})
class AdminStatisticsPostgresTest {
    static final Instant NOW=Instant.parse("2026-11-28T05:00:00Z");
    static final Instant DAY=Instant.parse("2026-11-01T05:00:00Z");
    static final String ALL="STATISTICS_VIEW,PAYMENT_VIEW,INCIDENT_VIEW";
    @SpringBootConfiguration @EntityScan("com.hs") @EnableMethodSecurity(proxyTargetClass=true)
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages="com.hs.contract.service.statistics")
    static class Config { @Bean BillingTime time(Environment e){return new BillingTime(NOW.toString(),e);} }
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdminStatisticsQueries queries;
    @Autowired AdminStatisticsService service;
    @Autowired AdminStatisticsController controller;
    @Autowired AdminStatisticsIndexMigration migration;
    MockMvc mvc;
    @BeforeEach void setup(){
        ((Map<?,?>)ReflectionTestUtils.getField(service,"cache")).clear();
        auth(ALL);
        mvc=standaloneSetup(controller).setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(AdminStatisticsControllerTest.SECRET)).build();
    }
    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    static void auth(String permissions){
        UserContextHolder.set(new UserContext("test-admin",null));
        var authorities=new ArrayList<SimpleGrantedAuthority>();authorities.add(new SimpleGrantedAuthority("ADMIN"));
        Arrays.stream(permissions.split(",")).forEach(p->authorities.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-admin",null,authorities));
    }
    AdminStatisticsQuery query(){return new AdminStatisticsQuery(LocalDate.of(2026,11,1),LocalDate.of(2026,11,3),AdminStatisticsQuery.Granularity.DAY);}
    AdminStatisticsResponse report(){return service.statistics(query());}
    static String id(){return UUID.randomUUID().toString();}
    <T> T save(T entity){if(entity instanceof BaseEntity b){if(b.getCreatedAt()==null)b.setCreatedAt(DAY);}em.persist(entity);em.flush();return entity;}
    static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    static void num(String expected,Object actual){assertNotNull(actual);assertEquals(new BigDecimal(expected).stripTrailingZeros(),new BigDecimal(actual.toString()).stripTrailingZeros());}
    void healthy(AdminStatisticsResponse r){assertEquals(List.of(),r.degraded());assertTrue(r.sources().values().stream().filter(x->x.available()).count()>25);}
    Listing listing(String owner,Instant submitted,String status){
        var l=Listing.builder().id(id()).ownerId(owner).title("private title").description("private description")
                .category(ListingCategory.ROOM).status(ListingStatus.valueOf(status)).submittedAt(submitted)
                .publishedAt(status.equals("PUBLISHED")?submitted:null).areaM2(BigDecimal.TEN).priceAmount(BigDecimal.TEN)
                .currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY).depositType(DepositType.NONE)
                .availableFrom(LocalDate.of(2026,11,1)).minimumLeaseMonths(1).build();
        l.setCreatedAt(submitted==null?DAY:submitted);return save(l);
    }
    Contract contract(String parent,Instant created,Instant signed){
        var c=Contract.builder().id(id()).contractNumber(id()).rentalRequestId(id()).listingId(id()).landlordId("secret-owner")
                .tenantId("secret-tenant").templateId(id()).templateVersionId(id()).parentContractId(parent)
                .status(ContractStatus.ACTIVE).signedAt(signed).build();c.setCreatedAt(created);return save(c);
    }
    PaymentRequest payment(String type,String direction,String status,String amount,Instant confirmed,String contract){
        return save(PaymentRequest.builder().payerId("secret-payer").payeeId("secret-payee").contractId(contract)
                .type(PaymentType.valueOf(type)).direction(PaymentDirection.valueOf(direction)).status(PaymentStatus.valueOf(status))
                .totalAmount(new BigDecimal(amount)).confirmedAt(confirmed).transferReference(id().substring(0,18)).build());
    }
    void event(PaymentRequest p,String event,String actor){save(PaymentEvent.builder().paymentRequestId(p.getId()).eventType(PaymentEventType.valueOf(event)).actorId(actor).createdAt(DAY).reason("SECRET REASON").build());}
    void order(String kind,String tier,String provider,String status,int amount,Instant paid){
        var o=new MembershipOrder();o.setId(id().replace("-",""));o.setOwnerId("secret-owner");o.setKind(MembershipKind.valueOf(kind));
        if(tier!=null)o.setTier(MembershipTier.valueOf(tier));o.setProvider(provider==null?null:MembershipOrder.Provider.valueOf(provider));
        o.setStatus(MembershipOrder.Status.valueOf(status));o.setAmountVnd(amount);o.setCreatedAt(DAY);o.setExpiresAt(DAY.plusSeconds(100));o.setPaidAt(paid);save(o);
    }
    void period(String tier,Instant start,Instant end,int quota,int used,int reserved){
        var p=new MembershipPeriod();p.setOwnerId(id());p.setTier(MembershipTier.valueOf(tier));p.setStartAt(start);p.setEndAt(end);p.setQuota(quota);p.setUsed(used);p.setReserved(reserved);save(p);
    }
    void incident(String component,Instant opened,Instant resolved,boolean open){
        var i=new OperationalIncident();i.setId(id());i.setSourceKey("SECRET-"+id());i.setResourceId("SECRET-resource");i.setComponent(component);i.setOpenedAt(opened);i.setResolvedAt(resolved);i.setOpen(open);save(i);
    }
    void rental(Listing listing,String status,Instant responded){save(RentalRequest.builder().id(id()).listing(listing).ownerId("secret-owner").renterId("secret-renter")
            .renterName("SECRET NAME").renterPhone("0900000000").renterEmail("secret@example.com").leaseMonths(12).moveInDate(LocalDate.of(2026,12,1))
            .monthlyRentPrice(BigDecimal.TEN).status(RentalRequestStatus.valueOf(status)).respondedAt(responded).build());}
    void appointment(Listing listing,String status,Instant responded){save(ViewingAppointment.builder().id(id()).listing(listing).ownerId("secret-owner").renterId("secret-renter")
            .renterName("SECRET NAME").renterPhone("0900000000").appointmentDate(LocalDate.of(2026,11,2)).startTime(LocalTime.NOON).endTime(LocalTime.of(13,0))
            .status(AppointmentStatus.valueOf(status)).respondedAt(responded).build());}
    void invoice(String status,Instant due,String fee){save(MonthlyInvoice.builder().contractId(id()).periodIndex(1).periodStart(LocalDate.of(2026,11,1))
            .periodEndExclusive(LocalDate.of(2026,12,1)).status(MonthlyInvoiceStatus.valueOf(status)).totalAmount(BigDecimal.TEN).dueAt(due).lateFeeAmount(fee==null?null:new BigDecimal(fee)).build());}
    void history(Listing l,String target,String actor,long seconds){save(ListingStatusHistory.builder().listingId(l.getId()).fromStatus(ListingStatus.PENDING_REVIEW)
            .toStatus(ListingStatus.valueOf(target)).changedByType(ListingStatusActorType.valueOf(actor)).changedBy("SECRET-admin").createdAt(DAY.plusSeconds(seconds)).build());}
    void user(String roleName,Instant created,boolean onboarded,boolean verified){
        var role=save(Role.builder().name(roleName).build());
        var u=new User();u.setId(id());u.setUsername(id());u.setEmail(id()+"@example.com");u.setRole(role);u.setOnBoarded(onboarded);u.setCreatedAt(created);save(u);
        if(verified) save(KycVerification.builder().id(id()).userId(u.getId()).provider(KycProvider.VNPT).status(KycStatus.VERIFIED).providerSessionId(id()).build());
    }
    void address(Listing l,String city){var a=new ListingAddress();a.setListingId(l.getId());a.setCity(city);a.setCityId(id().substring(0,8));a.setDisplay("SECRET address");a.setAddress("SECRET address");a.setStreet("SECRET street");a.setWard("SECRET ward");a.setLatitude(BigDecimal.ONE);a.setLongitude(BigDecimal.ONE);save(a);}

    @Test void emptyDatasetExecutesEveryQueryAndReturnsHonestEmptyObservations() throws Exception {
        var r=report();healthy(r);
        assertEquals(List.of("2026-11-01","2026-11-02","2026-11-03"),r.series().get("buckets"));
        num("0",map(r.kpi().get("gmvVnd")).get("value"));
        assertNull(map(r.breakdown().get("incidents")).get("mttrMedianSec"));
        assertNull(map(r.breakdown().get("moderation")).get("reviewP90Sec"));
        num("0",map(r.funnel().get("signedToPaid")).get("den"));
        num("0",map(map(r.breakdown().get("responseSla")).get("rentalRequests")).get("observations"));
        mvc.perform(AdminStatisticsControllerTest.auth("ADMIN",ALL).param("from","2026-11-01").param("to","2026-11-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.degraded").isEmpty()).andExpect(jsonPath("$.result.breakdown.incidents.mttrP90Sec").value((Object)null));
    }
    @Test void mixedFinancialDataExcludesRefundsUnconfirmedWrongDirectionAndCurrency() throws Exception {
        var c=contract(null,DAY,DAY);contract(c.getId(),DAY,DAY);contract(null,DAY.minusSeconds(40*86400),DAY.minusSeconds(40*86400));
        var p=payment("INITIAL","TENANT_TO_LANDLORD","CONFIRMED","1000.25",Instant.parse("2026-10-31T17:00:00Z"),c.getId());
        payment("MONTHLY_RENT","TENANT_TO_LANDLORD","CONFIRMED","50.50",Instant.parse("2026-11-03T16:59:59Z"),null);
        payment("RESERVATION_DEPOSIT","TENANT_TO_LANDLORD","CONFIRMED","100",DAY,null);
        payment("UTILITY","TENANT_TO_LANDLORD","CONFIRMED","20",DAY,null);
        payment("OTHER_CHARGE","TENANT_TO_LANDLORD","CONFIRMED","10",DAY,null);
        payment("DEPOSIT_REFUND","LANDLORD_TO_TENANT","CONFIRMED","99999",DAY,null);
        payment("INITIAL_PAYMENT_REFUND","LANDLORD_TO_TENANT","CONFIRMED","99999",DAY,null);
        payment("DEPOSIT_ADJUSTMENT","TENANT_TO_LANDLORD","CONFIRMED","99999",DAY,null);
        payment("MONTHLY_RENT","LANDLORD_TO_TENANT","CONFIRMED","99999",DAY,null);
        payment("MONTHLY_RENT","TENANT_TO_LANDLORD","REJECTED","99999",DAY,null);
        var usd=payment("MONTHLY_RENT","TENANT_TO_LANDLORD","CONFIRMED","99999",DAY,null);usd.setCurrency("USD");em.flush();
        var inactive=payment("MONTHLY_RENT","TENANT_TO_LANDLORD","CONFIRMED","99999",DAY,null);inactive.setActive(false);em.flush();
        payment("MONTHLY_RENT","TENANT_TO_LANDLORD","CONFIRMED","99999",Instant.parse("2026-11-03T17:00:00Z"),null);
        payment("MONTHLY_RENT","TENANT_TO_LANDLORD","CONFIRMED","9",Instant.parse("2026-10-31T16:59:59Z"),null);
        event(p,"RECEIPT_CONFIRMED","SYSTEM");event(p,"RECEIPT_CONFIRMED","human");event(p,"RECEIPT_REJECTED","human");event(p,"TRANSFER_REPORTED","human");
        var refund=payment("DEPOSIT_REFUND","LANDLORD_TO_TENANT","CONFIRMED","100",DAY,null);event(refund,"RECEIPT_CONFIRMED","SYSTEM");
        order("PLAN","PRO","SEPAY","PAID",100,DAY);order("ADDON",null,"ETH","PAID",50,DAY);order("PLAN","STARTER","VNPAY","PAID",20,DAY);
        for(String s:List.of("PENDING","EXPIRED","CANCELLED","FAILED","REVIEW"))order("PLAN","PRO","SEPAY",s,99999,null);
        var r=report();healthy(r);
        num("1180.75",map(r.kpi().get("gmvVnd")).get("value"));num("9",map(r.kpi().get("gmvVnd")).get("previous"));
        num("5",map(r.breakdown().get("gmv")).get("confirmedTransactions"));
        var gmv=map(r.series().get("gmvByType"));num("0",((List<?>)gmv.get("INITIAL")).get(1));num("50.50",((List<?>)gmv.get("MONTHLY_RENT")).get(2));
        num("1",map(r.funnel().get("signedToPaid")).get("num"));num("1",map(r.funnel().get("contractCreatedToSigned")).get("den"));
        num("170",map(r.kpi().get("membershipRevenueVnd")).get("value"));num("50",((List<?>)map(r.series().get("membershipByTier")).get("ADDON")).getFirst());
        num("1",((List<?>)r.series().get("autoConfirmedRent")).getFirst());num("1",((List<?>)r.series().get("autoConfirmedRefund")).getFirst());
        num("1",map(map(r.breakdown().get("paymentEvents")).get("rejectRate")).get("num"));
        var response=mvc.perform(AdminStatisticsControllerTest.auth("ADMIN",ALL).param("from","2026-11-01").param("to","2026-11-03")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        for(String forbidden:List.of("secret-owner","secret-payer","SECRET REASON","sourceKey","resourceId","payerId","ownerId","email","bankAccount","transactionNo"))assertFalse(response.contains(forbidden),forbidden);
    }
    @Test void growthCohortsVietnamDaysMonthEdgesAndCityCapAreAccurate() {
        user("USER",Instant.parse("2026-10-31T16:59:59Z"),false,false);
        // Reuse the USER role for additional rows to respect the unique role catalog.
        Role role=em.createQuery("from Role where name='USER'",Role.class).getSingleResult();
        for(Instant t:List.of(Instant.parse("2026-10-31T17:00:00Z"),Instant.parse("2026-11-01T16:30:00Z"),Instant.parse("2026-11-02T17:10:00Z"))){var u=new User();u.setId(id());u.setUsername(id());u.setEmail(id()+"@example.com");u.setRole(role);u.setCreatedAt(t);u.setOnBoarded(true);save(u);}
        user("ADMIN",DAY,true,true);
        listing("prior-owner",DAY.minusSeconds(40*86400),"PUBLISHED");listing("prior-owner",DAY,"PUBLISHED");
        for(int i=0;i<13;i++)address(listing("owner-"+i,DAY,"PUBLISHED"),"City "+i);
        listing("draft",null,"DRAFT");var inactive=listing("inactive",DAY,"PUBLISHED");inactive.setActive(false);em.flush();
        var r=report();healthy(r);
        assertEquals(List.of(new BigDecimal("2"),BigDecimal.ZERO,BigDecimal.ONE),r.series().get("newUsers"));
        num("3",map(r.kpi().get("newUsers")).get("value"));num("1",map(r.kpi().get("newUsers")).get("previous"));
        num("3",map(r.funnel().get("registeredToOnboarded")).get("num"));
        num("14",map(r.kpi().get("submittedListings")).get("value"));
        num("14",((List<?>)r.series().get("newLandlords")).getFirst()); // First active listing, including the unsent draft.
        assertEquals(11,((List<?>)r.breakdown().get("listingsByCity")).size());
        var monthly=service.statistics(new AdminStatisticsQuery(LocalDate.of(2026,10,31),LocalDate.of(2026,11,1),AdminStatisticsQuery.Granularity.MONTH));healthy(monthly);
        assertEquals(List.of("2026-10","2026-11"),monthly.series().get("buckets"));
        assertEquals(List.of(BigDecimal.ONE,new BigDecimal("2")),monthly.series().get("newUsers"));
    }
    @Test void responseModerationAndIncidentPercentilesUseRealObservationsOnly() {
        var l=listing("owner",DAY,"PUBLISHED");
        for(long seconds:List.of(10L,20L,100L)){rental(l,"ACCEPTED",DAY.plusSeconds(seconds));appointment(l,"CONFIRMED",DAY.plusSeconds(seconds));history(l,seconds==10?"PUBLISHED":seconds==20?"REJECTED":"VIOLATION","ADMIN",seconds);incident("BLOCKCHAIN",DAY,DAY.plusSeconds(seconds),false);}
        rental(l,"REJECTED",null);rental(l,"REJECTED",DAY.minusSeconds(1));appointment(l,"COMPLETED",null);
        history(l,"EXPIRED","SYSTEM",200);incident("BLOCKCHAIN",DAY,null,true);incident("PDF",DAY,DAY.minusSeconds(1),false);
        var r=report();healthy(r);
        var sla=map(map(r.breakdown().get("responseSla")).get("rentalRequests"));num("3",sla.get("observations"));num("20",sla.get("medianSec"));num("84",sla.get("p90Sec"));num("1",sla.get("unmeasured"));
        var moderation=map(r.breakdown().get("moderation"));num("20",moderation.get("reviewMedianSec"));num("84",moderation.get("reviewP90Sec"));
        num("1",map(moderation.get("rejectRate")).get("num"));num("3",map(moderation.get("violationRate")).get("den"));
        var incidents=map(r.breakdown().get("incidents"));num("20",incidents.get("mttrMedianSec"));num("84",incidents.get("mttrP90Sec"));num("1",incidents.get("open"));assertEquals(List.of("BLOCKCHAIN"),incidents.get("repeatComponents"));
        assertEquals("PAST_SCHEDULE_NOT_ATTENDANCE",map(r.breakdown().get("appointments")).get("completedMeaning"));
    }
    @Test void singleObservationHasNoP90AndBusinessDeadlinesRespectExactBoundary() {
        var l=listing("owner",DAY,"PUBLISHED");rental(l,"ACCEPTED",DAY.plusSeconds(12));
        period("PRO",NOW.minusSeconds(1),NOW.plusSeconds(7*86400),10,7,3);
        period("STARTER",NOW.minusSeconds(100),NOW,10,0,0);
        period("BUSINESS",NOW.plusSeconds(1),NOW.plusSeconds(86400),10,0,0);
        invoice("DRAFT",DAY,"25");invoice("PAID",DAY,null);invoice("OVERDUE",DAY,"10");invoice("DRAFT",NOW,"9999");
        l.setExpiresAt(Instant.now().minus(Duration.ofDays(1)));
        var review=listing("review",DAY,"PENDING_REVIEW");review.setReviewOperationalStatus(ListingStatus.PUBLISHED);review.setExpiresAt(Instant.now().plus(Duration.ofDays(1)));
        var forced=contract(null,DAY,DAY);forced.setTerminationCompletedAt(DAY);forced.setTerminationForcedAt(DAY);
        var mutual=contract(null,DAY,DAY);mutual.setTerminationCompletedAt(DAY);
        var declined=contract(null,DAY,DAY);declined.setTerminationProposedAt(DAY);declined.setTerminationDeclinedAt(DAY);em.flush();
        var r=report();healthy(r);
        var sla=map(map(r.breakdown().get("responseSla")).get("rentalRequests"));num("12",sla.get("medianSec"));assertNull(sla.get("p90Sec"));
        var active=map(r.breakdown().get("membershipActive"));num("1",active.get("exhausted"));num("1",active.get("expiring7d"));assertEquals(Set.of("PRO"),map(active.get("byTier")).keySet());
        var invoices=map(r.breakdown().get("invoices"));num("1",invoices.get("draftOverdue"));num("35",invoices.get("lateFeeVnd"));
        num("1",map(r.breakdown().get("listingSupply")).get("overdueUnswept"));num("1",map(r.breakdown().get("listingSupply")).get("expiringIn7d"));
        var terminations=map(r.breakdown().get("terminations"));num("1",terminations.get("mutual"));num("1",terminations.get("unilateral"));num("1",map(terminations.get("rejectRate")).get("num"));assertNull(terminations.get("early"));
    }
    @Test void kycReasonsAreCategorizedAndLegacyNullsRemainUnmeasured() {
        for(String reason:List.of("Số định danh đã được xác minh trên tài khoản khác.","Giấy tờ hoặc khuôn mặt chưa đáp ứng yêu cầu xác minh. Vui lòng thử lại.","SECRET provider diagnostic"))
            save(KycVerification.builder().id(id()).userId(id()).provider(KycProvider.VNPT).status(KycStatus.REJECTED).providerSessionId(id()).rejectionReason(reason).build());
        var manual=save(KycVerification.builder().id(id()).userId(id()).provider(KycProvider.ADMIN).status(KycStatus.VERIFIED).providerSessionId(id()).verifiedAt(DAY).build());
        var l=listing("owner",DAY,"PUBLISHED");rental(l,"ACCEPTED",null);appointment(l,"COMPLETED",null);
        jdbc.update("update rental_requests set updated_at=?",java.sql.Timestamp.from(DAY.plusSeconds(20)));
        var r=report();healthy(r);
        assertEquals(Map.of("DUPLICATE_ID",1L,"DOCUMENT_OR_FACE",1L,"OTHER",1L),r.breakdown().get("kycRejectByCategory"));
        num("1",((List<?>)r.series().get("kycAdminManual")).getFirst());
        assertNull(map(map(r.breakdown().get("responseSla")).get("rentalRequests")).get("medianSec"));
        num("0",map(map(r.breakdown().get("responseSla")).get("rentalRequests")).get("observations"));
        assertTrue((Boolean)map(r.breakdown().get("responseSla")).get("available"));
    }
    @Test void absentLegacyColumnAndTableDegradeOnlyTheirBlockWithoutAbortingTransaction() {
        jdbc.execute("alter table rental_requests rename column responded_at to old_responded_at");
        jdbc.execute("alter table operational_incidents rename to old_operational_incidents");
        migration.migrate();
        var r=report();assertEquals(Set.of("rentalResponseSla","incidents"),new HashSet<>(r.degraded()));
        assertFalse(r.sources().get("incidents").available());assertNull(r.breakdown().get("incidents"));
        assertEquals(false,map(r.breakdown().get("responseSla")).get("available"));
        assertNotNull(r.breakdown().get("invoices"));assertNotNull(r.breakdown().get("moderation"));
    }
    @Test void statementTimeoutRollsBackToSavepointAndAllowsFollowingQueries() {
        var timeout=queries.read("select pg_sleep(10)",query(),NOW,Instant.now());
        assertEquals("QUERY_FAILED",timeout.failure());assertNull(timeout.data());
        var next=queries.read("select 1 value",query(),NOW,Instant.now());
        assertNull(next.failure());num("1",next.data().getFirst().get("value"));
    }
    @Test void supplementaryPermissionsAreAppliedToHttpResponseAndCache() throws Exception {
        incident("PDF",DAY,DAY.plusSeconds(5),false);
        mvc.perform(AdminStatisticsControllerTest.auth("ADMIN",ALL).param("from","2026-11-01").param("to","2026-11-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.breakdown.incidents.byComponent.PDF").value(1));
        mvc.perform(AdminStatisticsControllerTest.auth("ADMIN","STATISTICS_VIEW").param("from","2026-11-01").param("to","2026-11-03"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.breakdown.incidents").value((Object)null))
                .andExpect(jsonPath("$.result.breakdown.paymentEvents").value((Object)null))
                .andExpect(jsonPath("$.result.series.autoConfirmedRent").value((Object)null))
                .andExpect(jsonPath("$.result.sources.incidents.limit").value("INCIDENT_VIEW_REQUIRED"));
        mvc.perform(AdminStatisticsControllerTest.auth("USER",ALL)).andExpect(status().isForbidden());
        mvc.perform(AdminStatisticsControllerTest.auth("ADMIN","PAYMENT_VIEW")).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(AdminStatisticsControllerTest.URL)).andExpect(status().isUnauthorized());
    }
    @Test void indexesAreIdempotentOnRealSchemaAndCacheIsBoundedAndPermissionScoped() {
        migration.migrate();migration.migrate();
        num("16",jdbc.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and indexname like 'idx_stats_%'",Long.class));
        var first=report();assertSame(first,report());auth("STATISTICS_VIEW");assertNotSame(first,report());
        for(int i=1;i<=34;i++)service.statistics(new AdminStatisticsQuery(LocalDate.of(2026,9,1).plusDays(i),LocalDate.of(2026,9,1).plusDays(i),AdminStatisticsQuery.Granularity.DAY));
        assertEquals(32,((Map<?,?>)ReflectionTestUtils.getField(service,"cache")).size());
    }
}
