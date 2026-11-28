package com.hs.api.controller.admin;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.context.*;
import com.hs.common.persistence.BaseEntity;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.admin.AdminDashboardResponse;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.notification.ContractNotificationOutbox;
import com.hs.contract.config.AdminDashboardIndexMigration;
import com.hs.contract.blockchain.persistence.*;
import com.hs.payment.membership.MembershipOverpayment;
import com.hs.payment.receipt.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.listing.nearby.model.NearbyScanJob;
import com.hs.contract.service.admin.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.user.model.*;
import com.hs.user.model.Role;
import com.hs.user.model.constant.*;
import com.hs.user.service.kyc.AdminKycQueueService;
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
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@EnabledIfEnvironmentVariable(named="HS_P4_TEST_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_admin_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=AdminDashboardPostgresTest.Config.class)
@TestPropertySource(properties={"spring.datasource.url=${HS_P4_TEST_PG_URL}","spring.datasource.username=postgres","spring.datasource.password=p4_disposable","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"})
@ActiveProfiles("test")
@Import({AdminDashboardQueries.class,AdminDashboardService.class,AdminDashboardController.class,AdminSystemHealthController.class,AdminKycQueueService.class,AdminKycQueueController.class,AdminDashboardIndexMigration.class})
class AdminDashboardPostgresTest {
    static final Instant BUSINESS=Instant.parse("2026-11-28T05:00:00Z"),TODAY=Instant.parse("2026-11-27T17:00:00Z");
    static final String ALL="LISTING_VIEW,USER_VIEW,KYC_VIEW,STATISTICS_VIEW,CONTRACT_VIEW,COMPLAINT_VIEW,INCIDENT_VIEW,PAYMENT_VIEW,SYSTEM_HEALTH_VIEW,CONTRACT_TEMPLATE_VIEW,NEARBY_SCAN_VIEW";
    @SpringBootConfiguration @EntityScan("com.hs") @EnableMethodSecurity(proxyTargetClass=true)
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages="com.hs.contract.service.admin")
    static class Config {
        @Bean BillingTime time(Environment e){return new BillingTime(BUSINESS.toString(),e);}
        @Bean AdminServiceDiscovery discovery(){return mock(AdminServiceDiscovery.class);}
    }
    @Autowired EntityManager em;@Autowired JdbcTemplate jdbc;@Autowired AdminDashboardQueries queries;
    @Autowired AdminDashboardService service;@Autowired AdminKycQueueService kyc;@Autowired AdminServiceDiscovery discovery;
    @Autowired AdminDashboardIndexMigration migration;
    @Autowired AdminDashboardController overviewController;@Autowired AdminSystemHealthController healthController;@Autowired AdminKycQueueController kycController;
    MockMvc mvc;Instant real;
    @BeforeEach void setup(){real=Instant.now();for(String field:List.of("overviewCache","healthCache","refreshes"))((Map<?,?>)ReflectionTestUtils.getField(service,field)).clear();
        reset(discovery);when(discovery.snapshot(any())).thenReturn(List.of(new AdminServiceDiscovery.Service("hs-core-api","UP",1,1L,real.minusSeconds(100))));auth(ALL);
        mvc=standaloneSetup(overviewController,healthController,kycController).setControllerAdvice(new GlobalException()).addFilters(new UserContextFilter(AdminDashboardControllerTest.SECRET)).build();}
    @AfterEach void clear(){UserContextHolder.clear();SecurityContextHolder.clearContext();}
    static String id(){return UUID.randomUUID().toString();}
    static void auth(String permissions){UserContextHolder.set(new UserContext("test-admin",null));var a=new ArrayList<SimpleGrantedAuthority>();a.add(new SimpleGrantedAuthority("ADMIN"));Arrays.stream(permissions.split(",")).filter(x->!x.isBlank()).forEach(p->a.add(new SimpleGrantedAuthority(p)));SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test-admin",null,a));}
    <T> T save(T entity){if(entity instanceof BaseEntity b&&b.getCreatedAt()==null)b.setCreatedAt(real);em.persist(entity);em.flush();return entity;}
    static void num(long expected,Object actual){assertNotNull(actual);assertEquals(expected,((Number)actual).longValue());}
    static Map<String,Object> map(Object o){return (Map<String,Object>)o;}
    User user(String roleName,Instant created,boolean active){var roles=em.createQuery("from Role where name=:name",Role.class).setParameter("name",roleName).getResultList();var role=roles.isEmpty()?save(Role.builder().name(roleName).build()):roles.getFirst();var u=new User();u.setId(id());u.setUsername(id());u.setEmail("SECRET-"+id()+"@example.com");u.setRole(role);u.setCreatedAt(created);u.setOnBoarded(false);u.setActive(active);return save(u);}
    KycVerification attempt(User u,String provider,String status,Instant created,Instant expires,Instant verified,String reason){var k=KycVerification.builder().id(id()).userId(u.getId()).provider(KycProvider.valueOf(provider)).status(KycStatus.valueOf(status)).providerSessionId("SECRET-"+id()).expiresAt(expires).verifiedAt(verified).rejectionReason(reason).build();k.setCreatedAt(created);return save(k);}
    Listing listing(String status,Instant expires){return save(Listing.builder().id(id()).ownerId("SECRET-owner").title("SECRET title").description("SECRET description").category(ListingCategory.ROOM).status(ListingStatus.valueOf(status)).submittedAt(real.minusSeconds(400)).expiresAt(expires).areaM2(BigDecimal.TEN).priceAmount(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY).depositType(DepositType.NONE).availableFrom(LocalDate.of(2026,11,1)).minimumLeaseMonths(1).build());}
    Contract contract(String status){return save(Contract.builder().id(id()).contractNumber(id()).rentalRequestId(id()).listingId(id()).landlordId("SECRET-owner").tenantId("SECRET-tenant").templateId(id()).templateVersionId(id()).status(ContractStatus.valueOf(status)).build());}
    PaymentRequest payment(String type,String direction,String status,Instant confirmed){return save(PaymentRequest.builder().payerId("SECRET-payer").payeeId("SECRET-payee").type(PaymentType.valueOf(type)).direction(PaymentDirection.valueOf(direction)).status(PaymentStatus.valueOf(status)).totalAmount(BigDecimal.TEN).confirmedAt(confirmed).confirmationDueAt(BUSINESS.minusSeconds(400)).transferReference(id().substring(0,18)).build());}
    void order(String status,int amount,Instant paid,Instant expires){var o=new MembershipOrder();o.setId(id().replace("-",""));o.setOwnerId("SECRET-owner");o.setStatus(MembershipOrder.Status.valueOf(status));o.setAmountVnd(amount);o.setCreatedAt(real);o.setExpiresAt(expires);o.setPaidAt(paid);save(o);}
    void invoice(String status,Instant due){save(MonthlyInvoice.builder().contractId(id()).periodIndex(1).periodStart(LocalDate.of(2026,11,1)).periodEndExclusive(LocalDate.of(2026,12,1)).status(MonthlyInvoiceStatus.valueOf(status)).dueAt(due).totalAmount(BigDecimal.TEN).build());}
    RentalRequest rental(Listing l,Instant hold){return save(RentalRequest.builder().id(id()).listing(l).ownerId("SECRET-owner").renterId("SECRET-renter").renterName("SECRET NAME").renterPhone("0900000000").moveInDate(LocalDate.of(2026,12,1)).leaseMonths(12).monthlyRentPrice(BigDecimal.TEN).status(RentalRequestStatus.ACCEPTED).holdExpiresAt(hold).build());}
    DisputeCase complaint(String assigned,Instant escalation){return save(DisputeCase.builder().subjectType("RENTAL").subjectId(id()).payerId("SECRET-payer").payeeId("SECRET-payee").openedBy("SECRET").reason("SECRET REASON").openedAt(real.minusSeconds(100000)).status(DisputeCaseStatus.ESCALATED).amountSnapshot(BigDecimal.TEN).currency("VND").initialEvidenceSnapshot("SECRET").complaintStage("WAITING_ADMIN").respondBy(BUSINESS.minusSeconds(1)).escalatedAt(escalation).assignedTo(assigned).build());}
    @Test void emptyDatasetExecutesAllQueriesAndMissingCaptureIsExplicit(){
        var o=service.overview(false);assertEquals(List.of(),o.degraded());num(0,o.attention().get("pendingListings"));assertEquals(7,((List<?>)o.users().get("daily7d")).size());
        var h=service.health(false);assertEquals(List.of(),h.degraded());assertEquals("UNKNOWN",h.level());assertEquals(8,h.outbox().size());assertNull(h.jobs().get("recorded"));assertFalse(h.sources().get("jobRuns").available());
        for(var row:h.outbox()){num(0,row.get("pending"));assertNull(row.get("retrying"));assertNull(row.get("dead"));assertEquals(false,row.get("frozen"));}
        assertNull(jdbc.queryForObject("select to_regclass('scheduled_job_runs')",String.class));assertEquals(true,h.responseSla().get("respondedAtAvailable"));
        assertEquals("OK",h.payments().get("level"));assertEquals(0,kyc.list(AdminKycQueueService.Query.parse(Map.of(),true)).total());
    }
    @Test void narrowPermissionsFilterHttpBlocksAndDoNotLeakCachedPrivilegedData() throws Exception {
        mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.OVERVIEW,"ADMIN",ALL)).andExpect(status().isOk());
        var body=mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.OVERVIEW,"ADMIN","LISTING_VIEW")).andExpect(status().isOk())
            .andExpect(jsonPath("$.result.attention.pendingListings").value(0)).andExpect(jsonPath("$.result.attention.pendingKyc").value((Object)null)).andExpect(jsonPath("$.result.users").value((Object)null)).andExpect(jsonPath("$.result.rentals").value((Object)null)).andExpect(jsonPath("$.result.membership").value((Object)null)).andExpect(jsonPath("$.result.system").value((Object)null)).andReturn().getResponse().getContentAsString();
        for(String leak:List.of("SECRET","sourceKey","resourceId","payload","email","userId","payerId","reason\""))assertFalse(body.contains(leak),leak);
        mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.HEALTH,"ADMIN","PAYMENT_VIEW")).andExpect(status().isOk()).andExpect(jsonPath("$.result.payments.membershipOrdersStuck").value(0)).andExpect(jsonPath("$.result.services").value((Object)null)).andExpect(jsonPath("$.result.outbox").value((Object)null)).andExpect(jsonPath("$.result.kyc").value((Object)null));
        mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.OVERVIEW,"ADMIN","STATISTICS_VIEW")).andExpect(status().isOk()).andExpect(jsonPath("$.result.membership.revenueToday").value(0));
    }
    @Test void vietnamDayBoundariesStatusesAndDisabledUsersAreAccurate(){
        user("USER",TODAY.minusSeconds(1),true);user("USER",TODAY,true);user("USER",TODAY.plusSeconds(23*3600+1800),true);user("ADMIN",TODAY,true);user("USER",TODAY,true).setActive(false);em.flush();
        payment("MONTHLY_RENT","TENANT_TO_LANDLORD","CONFIRMED",TODAY);payment("INITIAL","TENANT_TO_LANDLORD","CONFIRMED",TODAY.minusSeconds(1));payment("DEPOSIT_REFUND","LANDLORD_TO_TENANT","CONFIRMED",TODAY);payment("MONTHLY_RENT","TENANT_TO_LANDLORD","REJECTED",TODAY);
        order("PAID",100,TODAY,real);order("PAID",20,TODAY.minusSeconds(1),real);order("PENDING",9999,null,real.plusSeconds(60));contract("ACTIVE");var appendix=contract("ACTIVE");appendix.setParentContractId("parent");em.flush();
        var o=service.overview(false);assertEquals(List.of(),o.degraded());num(2,o.users().get("newToday"));num(3,o.users().get("new7d"));num(1,o.users().get("disabled"));num(1,o.users().get("activeAdmins"));num(1,o.rentals().get("confirmedPaymentsToday"));num(1,o.rentals().get("activeContracts"));num(100,o.membership().get("revenueToday"));num(120,o.membership().get("revenueMonth"));num(100,((List<?>)o.membership().get("daily7d")).getLast());
    }
    @Test void currentKycPrefersVerifiedAndAppliesRealSessionExpiryWithoutRawReasons() throws Exception {
        var verified=user("USER",real,true);attempt(verified,"VNPT","VERIFIED",real.minusSeconds(500),real.minusSeconds(100),real.minusSeconds(400),null);attempt(verified,"VNPT","PENDING",real,real.plusSeconds(100),null,null);
        attempt(verified,"DIDIT","VERIFIED",real.minusSeconds(1000),null,null,null);
        var expired=user("USER",real,true);attempt(expired,"VNPT","PENDING",real.minusSeconds(500),real.minusSeconds(1),null,null);
        var legacy=user("USER",real,true);attempt(legacy,"DIDIT","REVIEW_REQUIRED",real,real.plusSeconds(500),null,null);
        var rejected=user("USER",real,true);attempt(rejected,"ADMIN","REJECTED",real,real.plusSeconds(500),null,"SECRET provider diagnostic");
        var duplicate=user("USER",real,true);attempt(duplicate,"VNPT","REJECTED",real,real.plusSeconds(500),null,"Số định danh đã được xác minh trên tài khoản khác.");
        user("USER",real,true);
        var page=kyc.list(AdminKycQueueService.Query.parse(Map.of(),true));assertEquals(6,page.total());assertEquals("VERIFIED",page.items().stream().filter(x->x.userId().equals(verified.getId())).findFirst().orElseThrow().status());
        var originalSelection=em.createQuery("from KycVerification k where k.userId=:user and k.status=com.hs.user.model.constant.KycStatus.VERIFIED order by k.verifiedAt desc",KycVerification.class).setParameter("user",verified.getId()).setMaxResults(1).getSingleResult();
        assertEquals(originalSelection.getProvider().name(),page.items().stream().filter(x->x.userId().equals(verified.getId())).findFirst().orElseThrow().provider());
        assertEquals(2,page.items().stream().filter(x->x.status().equals("EXPIRED")).count());assertEquals("OTHER",page.items().stream().filter(x->x.userId().equals(rejected.getId())).findFirst().orElseThrow().rejectionCategory());
        var overdue=kyc.list(AdminKycQueueService.Query.parse(Map.of("overdue",new String[]{"false"}),true));assertTrue(overdue.items().stream().anyMatch(x->x.status().equals("NOT_VERIFIED")));
        assertEquals(1,kyc.list(AdminKycQueueService.Query.parse(Map.of("overdue",new String[]{"true"}),true)).total());
        var o=service.overview(false);num(1,o.users().get("kycVerified"));num(6,o.users().get("totalUsers"));num(2,o.attention().get("expiredKyc"));num(0,o.attention().get("pendingKyc"));
        var body=mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.KYC,"ADMIN","KYC_VIEW").param("status","REJECTED").param("size","1")).andExpect(status().isOk()).andExpect(jsonPath("$.result.total").value(2)).andExpect(jsonPath("$.result.items.length()").value(1)).andReturn().getResponse().getContentAsString();
        for(String leak:List.of("SECRET","email","cccd","providerSessionId","rejectionReason"))assertFalse(body.contains(leak),leak);
        mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.KYC+"/counts","ADMIN","KYC_VIEW")).andExpect(status().isOk()).andExpect(jsonPath("$.result").isArray());
    }
    @Test void legacyOutboxAgeDetectsFrozenAndOptionalRetryColumnsAreReadOnlyCapabilities(){
        save(new ContractNotificationOutbox(id(),id(),"topic","SECRET payload",real.minusSeconds(400)));
        var h=service.health(false);var row=h.outbox().stream().filter(x->x.get("table").equals("contract_notification_outbox")).findFirst().orElseThrow();assertEquals(true,row.get("frozen"));assertEquals("ERROR",row.get("level"));assertEquals("ERROR",h.level());assertNull(row.get("retrying"));
        jdbc.execute("alter table contract_notification_outbox add column attempts int not null default 0, add column failed_at timestamptz");jdbc.update("update contract_notification_outbox set attempts=3");
        ((Map<?,?>)ReflectionTestUtils.getField(service,"healthCache")).clear();row=service.health(false).outbox().stream().filter(x->x.get("table").equals("contract_notification_outbox")).findFirst().orElseThrow();num(1,row.get("retrying"));num(0,row.get("dead"));
        jdbc.update("update contract_notification_outbox set failed_at=?",Timestamp.from(real));((Map<?,?>)ReflectionTestUtils.getField(service,"healthCache")).clear();row=service.health(false).outbox().stream().filter(x->x.get("table").equals("contract_notification_outbox")).findFirst().orElseThrow();num(0,row.get("pending"));num(1,row.get("dead"));assertEquals(false,row.get("frozen"));
    }
    @Test void operationalAndBusinessBacklogsUseCorrectClockAndGrace(){
        listing("PUBLISHED",real.minusSeconds(180));listing("PUBLISHED",real.minusSeconds(30));var reserved=listing("RESERVED",null);rental(reserved,BUSINESS.minusSeconds(180));rental(reserved,BUSINESS.minusSeconds(30));
        var paidHold=rental(reserved,BUSINESS.minusSeconds(180));var paid=payment("INITIAL","TENANT_TO_LANDLORD","CONFIRMED",TODAY);paid.setRentalRequestId(paidHold.getId());
        var signingHold=rental(reserved,BUSINESS.minusSeconds(180));var signing=contract("PENDING_REVIEW");signing.setRentalRequestId(signingHold.getId());
        var disputedHold=rental(reserved,BUSINESS.minusSeconds(180));var dispute=complaint(null,real);dispute.setRentalRequestId(disputedHold.getId());dispute.setActiveKey("test-"+id());em.flush();
        // rental_request_id is immutable to JPA after insertion; set the fixture link explicitly.
        jdbc.update("update dispute_cases set rental_request_id=? where id=?",disputedHold.getId(),dispute.getId());
        invoice("UNPAID",BUSINESS.minusSeconds(360));invoice("UNPAID",BUSINESS.minusSeconds(30));invoice("DRAFT",BUSINESS.minusSeconds(3600));
        save(ContractClosure.builder().contractId(id()).status("REFUND_PENDING").refundDueAt(BUSINESS.minusSeconds(1)).build());
        save(ContractRenewal.builder().contractId(id()).months(12).status("REQUESTED").respondBy(BUSINESS.minusSeconds(180)).build());
        save(ContractRenewal.builder().contractId(id()).months(12).status("APPROVED").signBy(BUSINESS.minusSeconds(180)).build());
        save(ContractAmendment.builder().contractId(id()).baseRevisionId(id()).proposerId(id()).recipientId(id()).status("REQUESTED").effectiveOn(LocalDate.of(2026,11,28)).changesSnapshot("{}").comparisonSnapshot("{}").reason("SECRET").respondBy(BUSINESS.minusSeconds(180)).build());
        save(ContractAmendment.builder().contractId(id()).baseRevisionId(id()).proposerId(id()).recipientId(id()).status("APPROVED").effectiveOn(LocalDate.of(2026,11,28)).changesSnapshot("{}").comparisonSnapshot("{}").reason("SECRET").signBy(BUSINESS.minusSeconds(180)).build());
        var c=contract("TENANT_SIGNATURE_PENDING");c.setTenantSigningDueAt(BUSINESS.minusSeconds(180));em.flush();
        var h=service.health(false);assertEquals(List.of(),h.degraded());var jobs=(List<Map<String,Object>>)h.jobs().get("overdueWork");
        for(String key:List.of("listing_expiry","rental_hold_expiry","monthly_invoice_overdue","closure_payment","renewal_response","renewal_signing","amendment_response","amendment_signing","tenant_signing"))num(1,jobs.stream().filter(x->x.get("key").equals(key)).findFirst().orElseThrow().get("count"));
    }
    @Test void partialSourceFailuresDoNotPoisonOtherQueriesAndDiscoveryFailureIsUnavailable(){
        jdbc.execute("alter table monthly_invoices rename to missing_monthly_invoices");jdbc.execute("alter table rental_requests rename column responded_at to old_responded_at");when(discovery.snapshot(any())).thenThrow(new IllegalStateException("SECRET connection string"));
        save(SignatureRequest.builder().contractId(id()).revisionId(id()).sourceDocumentId(id()).signerUserId("SECRET-user").signerRole(SignerRole.LANDLORD).docId(id()).status(SignatureRequestStatus.EXPIRED).build());
        var h=service.health(false);assertNull(h.services());assertTrue(h.degraded().contains("services"));assertTrue(h.degraded().contains("jobs.monthly_invoice_overdue"));assertNotNull(h.payments());assertEquals(false,h.responseSla().get("respondedAtAvailable"));
        num(1,h.signing().get("unmeasuredFailures"));assertEquals("UNKNOWN",h.signing().get("level"));
        assertNull(h.sources().get("services").source().contains("SECRET")?"leak":null);
    }
    @Test void paymentAndComplaintCountsAreBoundedAndFreshRequestsRespectCache(){
        order("PENDING",10,null,real.minusSeconds(1));order("REVIEW",10,null,real.plusSeconds(1));payment("MONTHLY_RENT","TENANT_TO_LANDLORD","TRANSFER_REPORTED",null);
        complaint(null,real.minusSeconds(90000));complaint("assigned",real.minusSeconds(90000));
        var first=service.health(false);num(1,first.payments().get("membershipOrdersStuck"));num(1,first.payments().get("legacyReview"));num(1,first.payments().get("autoConfirmOverdue"));num(1,first.complaints().get("escalatedUnassigned"));assertEquals("ERROR",first.complaints().get("level"));assertSame(first,service.health(false));
        var fresh=service.health(true);assertNotSame(first,fresh);assertSame(fresh,service.health(true));verify(discovery,times(2)).snapshot(any());
    }
    @Test void actualStatementTimeoutRecoversWithoutExposingSql(){var fail=queries.read("select pg_sleep(10)",BUSINESS,real);assertEquals("QUERY_FAILED",fail.failure());var next=queries.read("select 1 count",BUSINESS,real);assertNull(next.failure());num(1,next.rows().getFirst().get("count"));}
    @Test void financeSigningProofAndQualityQueriesReadRealPersistedSources() throws Exception {
        order("PENDING",10,null,real.minusSeconds(1));String orderId=jdbc.queryForObject("select id from membership_orders limit 1",String.class);
        var quote=new EthPaymentQuote();quote.setPaymentId(id());quote.setOrderId(orderId);quote.setPayerWallet("SECRET-wallet");quote.setRecipientWallet("SECRET-wallet");quote.setContractAddress("SECRET-contract");quote.setAmountVnd(BigDecimal.TEN);quote.setEthVnd(BigDecimal.TEN);quote.setAmountWei("10");quote.setPriceUpdatedAt(real);quote.setExpiresAt(real);quote.setSignature("SECRET-signature");quote.setTransactionHash("SECRET-tx");save(quote);
        var over=new MembershipOverpayment();over.setId(id().replace("-",""));over.setOrderId(orderId);over.setOwnerId("SECRET-owner");over.setProvider(MembershipOrder.Provider.SEPAY);over.setTransactionNo(id());over.setAmountVnd(99999);over.setReceivedAt(real.minusSeconds(400));save(over);
        var receipt=new PaymentReceipt();receipt.setId(id());receipt.setPaymentRequestId(id());receipt.setReceiptNumber(id());receipt.setLandlordId("SECRET-owner");receipt.setTenantId("SECRET-tenant");receipt.setIssuedAt(real);receipt.setSnapshotJson("SECRET bank snapshot");receipt.setSnapshotHash("hash");receipt.setPdfStatus(PaymentReceipt.PdfStatus.FAILED);save(receipt);
        var membershipReceipt=new MembershipReceipt();membershipReceipt.setId(id());membershipReceipt.setOrderId(orderId);membershipReceipt.setOwnerId("SECRET-owner");membershipReceipt.setReceiptNumber(id());membershipReceipt.setIssuedAt(real.minusSeconds(400));membershipReceipt.setSnapshotJson("SECRET wallet snapshot");membershipReceipt.setSnapshotHash("hash");save(membershipReceipt);
        var storage=StorageObject.builder().id(id()).originalName("SECRET").objectKey(id()).bucketName("SECRET").contentType("image/png").sizeBytes(1L).ownerId("SECRET-owner").purpose(StoragePurpose.PAYMENT_PROOF).visibility(StorageVisibility.PRIVATE).status(StorageStatus.PENDING).build();storage.setCreatedAt(real.minusSeconds(4000));save(storage);
        save(SignatureRequest.builder().contractId(id()).revisionId(id()).sourceDocumentId(id()).signerUserId("SECRET-user").signerRole(SignerRole.LANDLORD).docId(id()).status(SignatureRequestStatus.EMBEDDING).processingStartedAt(real.minusSeconds(400)).initiatedAt(real.minusSeconds(500)).build());
        save(SignatureRequest.builder().contractId(id()).revisionId(id()).sourceDocumentId(id()).signerUserId("SECRET-user").signerRole(SignerRole.LANDLORD).docId(id()).status(SignatureRequestStatus.FAILED).failedAt(real.minusSeconds(1)).build());
        var proof=new ContractDocumentProof();proof.setDocumentId(id());proof.setContractId(id());proof.setRevisionId(id());proof.setStorageObjectId(id());proof.setOnChainDocumentId(id());proof.setDocumentHash("hash");proof.setRegistryAddress("SECRET-address");proof.setChainId(1);proof.setStatus(ProofStatus.FAILED);save(proof);
        var doc=ContractDocument.builder().contractId(id()).revisionId(id()).templateVersionId(id()).documentType(ContractDocumentType.PDF).purpose(DocumentPurpose.PREVIEW).status(DocumentGenerationStatus.FAILED).integrityStatus("MISMATCH").integrityMessage("SECRET details").build();save(doc);
        var l=listing("PUBLISHED",real.plusSeconds(100));l.setPublicationReservation("PLAN");em.flush();
        var job=new NearbyScanJob();job.setListing(l);job.setLocationHash(id());job.setRadiusM(500);job.setMappingVersion("v1");job.setStatus("FAILED");save(job);
        var pending=user("USER",real,true);attempt(pending,"VNPT","REVIEW_REQUIRED",real.minusSeconds(90000),real.plusSeconds(1000),null,null);
        var step=new KycProviderStep();step.setId(id());step.setProvider(KycProvider.VNPT);step.setAttemptId(id());step.setKind("card");step.setState("DONE");step.setResultJson("SECRET biometric result");step.setCreatedAt(real.minusSeconds(90000));save(step);
        var h=service.health(false);assertEquals(List.of(),h.degraded());num(1,h.payments().get("ethUnsettled"));num(1,h.payments().get("overpaymentsAwaitingRefund"));num(1,h.payments().get("receiptPdfFailed"));num(1,h.payments().get("receiptPdfOverdue"));num(1,h.payments().get("storageStuck"));num(1,h.signing().get("stuck"));num(1,h.signing().get("failed24h"));num(1,h.blockchain().get("failed"));num(1,h.blockchain().get("integrityMismatch"));num(1,h.dataQuality().get("listingTypesWithoutTemplate"));num(1,h.dataQuality().get("stuckPublicationReservations"));num(1,h.dataQuality().get("nearbyJobsFailed"));num(1,h.kyc().get("tempEvidenceOverdue"));num(1,h.kyc().get("reviewRequired"));
        var body=mvc.perform(AdminDashboardControllerTest.auth(AdminDashboardControllerTest.HEALTH,"ADMIN",ALL)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();for(String leak:List.of("SECRET","payerWallet","sourceKey","resourceId","payload","email","userId","transactionHash","snapshotJson"))assertFalse(body.contains(leak),leak);
    }
    @Test void indexesAreIdempotentAndOptionalJobTableIsNeverCreated(){migration.migrate();migration.migrate();assertNull(jdbc.queryForObject("select to_regclass('scheduled_job_runs')",String.class));num(6,jdbc.queryForObject("select count(*) from pg_indexes where schemaname=current_schema() and indexname like 'idx_admin_%'",Long.class));}
    @Test void preexistingJobRunSourceIsBoundedAndNeverReturnsRawErrors(){
        jdbc.execute("create table scheduled_job_runs(job_name varchar(100) primary key,last_success_at timestamptz,consecutive_failures int,last_error_class varchar(200),expected_interval_ms bigint)");
        jdbc.update("insert into scheduled_job_runs values (?,?,?,?,?)","testJob",Timestamp.from(real.minusSeconds(500)),3,"SECRET connection password=secret",1000);
        var h=service.health(false);assertEquals(List.of(),h.degraded());var recorded=(List<Map<String,Object>>)h.jobs().get("recorded");assertEquals(1,recorded.size());assertEquals(true,recorded.getFirst().get("overdue"));assertNull(recorded.getFirst().get("lastErrorClass"));assertEquals("ERROR",h.level());
    }
}
