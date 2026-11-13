package com.hs.api.workflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.api.controller.contract.*;
import com.hs.api.controller.admin.ComplaintAdminController;
import com.hs.api.advice.handler.GlobalException;
import com.hs.common.context.*;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.*;
import com.hs.contract.repository.*;
import com.hs.contract.service.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.listing.service.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;
import java.net.http.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
/** Test-only loopback server: real controllers/services/JPA; test identity and external providers only are isolated. */
@EnabledIfEnvironmentVariable(named="HS_WORKFLOW_HTTP_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_workflow_http_test")
@SpringBootTest(classes=RentalWorkflowHttpSystemTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={
    "server.address=127.0.0.1","server.port=18197","spring.config.location=optional:classpath:/workflow-http-test-only.properties",
    "spring.datasource.url=${HS_WORKFLOW_HTTP_PG_URL}","spring.datasource.username=postgres","spring.datasource.password=hs_workflow_test",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.open-in-view=false","spring.profiles.active=test","eureka.client.enabled=false","spring.cloud.discovery.enabled=false"})
class RentalWorkflowHttpSystemTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration(excludeName={"org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration","org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration","org.springframework.boot.data.mongodb.autoconfigure.DataMongoRepositoriesAutoConfiguration"}) @EntityScan("com.hs") @EnableJpaAuditing @EnableMethodSecurity
    @EnableJpaRepositories(basePackageClasses={com.hs.listing.notification.RentalRequestNotificationOutboxRepository.class,ContractRepository.class,RentalRequestRepository.class,PaymentRequestRepository.class,StorageObjectRepository.class})
    @Import({com.hs.listing.notification.RentalRequestNotificationWriter.class,ContractLifecycleService.class,MonthlyBillingService.class,FinalRentCalculator.class,WorkflowDocumentService.class,WorkflowDocumentCommitter.class,WorkflowDocumentRetentionChecker.class,PaymentRequestService.class,DepositLedgerService.class,PaymentDisputeService.class,ComplaintCaseService.class,ComplaintEvidenceAccessChecker.class,RentalWorkflowSupport.class,PreTenancyResolutionService.class,MoveInHandoverService.class,MutualTerminationService.class,RentalWorkflowEvidenceAccessChecker.class,RentalWorkflowController.class,ComplaintController.class,ComplaintAdminController.class,ContractLifecycleController.class,GlobalException.class,Harness.class})
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean Clock time(Environment env){return new Clock(env);}
        @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(c->c.disable()).authorizeHttpRequests(a->a.anyRequest().permitAll()).addFilterBefore(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
                    if(req.getRequestURI().equals("/test-harness/ready")){chain.doFilter(req,res);return;}
                    var token=req.getHeader("Authorization");String actor=token!=null&&token.startsWith("Bearer hs-workflow-test-")?token.substring("Bearer hs-workflow-test-".length()):"";
                    if(!Set.of("owner","tenant","tenant2","admin").contains(actor)){res.sendError(401);return;}
                    UserContextHolder.set(new UserContext(actor,null));SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,"test only",List.of(new SimpleGrantedAuthority(actor.equals("admin")?"ADMIN":"USER"))));
                    try{chain.doFilter(req,res);}finally{UserContextHolder.clear();SecurityContextHolder.clearContext();}
                }
            },AnonymousAuthenticationFilter.class).build();
        }
    }
    static class Clock extends BillingTime {volatile Instant instant=Instant.parse("2026-12-13T03:00:00Z");Clock(Environment env){super("2026-12-13T10:00:00",env);}@Override public Instant now(){return instant;}@Override public LocalDate today(){return instant.atZone(ZONE).toLocalDate();}}
    @MockitoBean ContractAmendmentService amendments;
    @MockitoBean ContractTermsResolver terms;
    @MockitoBean ListingStatusService listingStatus;
    @MockitoBean ParkingReservationService parking;
    @MockitoBean BankAccountService banks;
    @MockitoBean VietQrProvider qr;
    @MockitoBean TransferReferenceGenerator references;
    @MockitoBean PaymentProofUploadSessionService uploads;
    @MockitoBean WorkflowPdfRenderer renderer;
    @MockitoBean com.hs.contract.pdf.PdfSignatureEngine pdf;
    @MockitoBean com.hs.storage.service.StorageService storage;
    @MockitoBean com.hs.contract.deposit.DepositAgreementService depositAgreements;
    @Autowired Harness harness;
    @Autowired ContractRevisionRepository revisions;
    @BeforeEach void mocks(){when(terms.at(any(),any())).thenAnswer(c->{Contract contract=c.getArgument(0);return revisions.findById(contract.getCurrentRevisionId()).orElseThrow();});var bank=BankAccount.builder().bankBin("970436").bankCode("VCB").bankName("Test only").accountNumber("123456").accountHolderName("TEST ONLY").build();when(banks.getDefaultRefundAccount(anyString())).thenReturn(bank);when(banks.getDefaultIncomingAccount(anyString())).thenReturn(bank);when(references.generateUniqueReference()).thenAnswer(c->"HS"+UUID.randomUUID().toString().replace("-","").substring(0,15));harness.initialize();}
    @Test void realHttpChecksAndOptionalBrowserSession() throws Exception {
        var client=HttpClient.newHttpClient();var ready=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18197/test-harness/ready")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,ready.statusCode());
        var denied=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18197/api/v1/rental-requests/unknown/workflow/move-in")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,denied.statusCode());
        if("1".equals(System.getenv("HS_WORKFLOW_BROWSER_WAIT"))){System.out.println("WORKFLOW_BROWSER_SERVER_READY");long end=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(8);while(!harness.isFinished()&&System.nanoTime()<end)Thread.sleep(200);assertTrue(harness.isFinished(),"Browser suite did not finish within 8 minutes");assertTrue(harness.hasPassed(),"Browser suite reported failure");}
    }
    @RestController static class Harness {
        final AtomicBoolean finished=new AtomicBoolean();volatile boolean browserPassed;volatile boolean initialized;
        @Autowired ListingRepository properties;@Autowired RentalRequestRepository rentals;@Autowired ContractRepository contracts;@Autowired ContractRevisionRepository revisions;
        @Autowired PaymentRequestRepository payments;@Autowired PaymentLineItemRepository lines;@Autowired DepositRecordRepository deposits;@Autowired MonthlyInvoiceRepository invoices;@Autowired Clock time;@Autowired StorageObjectRepository files;@Autowired PaymentRequestService paymentService;
        public void initialize(){initialized=true;}public boolean isFinished(){return finished.get();}public boolean hasPassed(){return browserPassed;}
        @GetMapping("/test-harness/ready") Map<String,Object> ready(){return Map.of("ready",initialized,"testOnly",true);}
        @PostMapping("/test-harness/finish") Map<String,Object> finish(@RequestBody Map<String,Boolean> p){browserPassed=Boolean.TRUE.equals(p.get("passed"));finished.set(true);return Map.of("finished",true);}
        @PostMapping("/test-harness/scenarios/{scenario}") @Transactional Map<String,String> seed(@PathVariable String scenario){
            if(!"owner".equals(UserContextHolder.get().userId()))throw new IllegalArgumentException("Fixture setup requires test owner");time.instant=Instant.parse("2026-12-13T03:00:00Z");boolean before=scenario.equals("before");
            var l=properties.saveAndFlush(Listing.builder().ownerId("owner").title("Browser fixture only").description("Isolated workflow test").status(before?ListingStatus.RESERVED:ListingStatus.RENTED).category(ListingCategory.ROOM).priceAmount(new BigDecimal("5000000")).areaM2(BigDecimal.TEN).currency("VND").priceUnit(PriceUnit.MONTH).paymentCycle(PaymentCycle.MONTHLY).depositType(DepositType.NONE).availableFrom(time.today()).minimumLeaseMonths(1).build());
            var r=rentals.saveAndFlush(RentalRequest.builder().id(UUID.randomUUID().toString()).listing(l).ownerId("owner").renterId("tenant").renterName("Người thuê thử").renterPhone("0900000000").status(before?RentalRequestStatus.ACCEPTED:RentalRequestStatus.COMPLETED).moveInDate(LocalDate.of(2026,10,12)).leaseMonths(3).monthlyRentPrice(new BigDecimal("5000000")).depositAmount(new BigDecimal("20000000")).build());
            var c=contracts.saveAndFlush(Contract.builder().contractNumber("HTTP-"+UUID.randomUUID()).rentalRequestId(r.getId()).listingId(l.getId()).landlordId("owner").tenantId("tenant").templateId("template").templateVersionId("v1").status(before?ContractStatus.DRAFT:ContractStatus.ACTIVE).signedAt(before?null:time.now().minusSeconds(30*86400L)).paymentStatus(ContractPaymentStatus.PAID).build());
            var rev=revisions.saveAndFlush(ContractRevision.builder().contract(c).revisionNumber(1).templateVersionId("v1").landlordSnapshot("{}").tenantSnapshot("{}").propertySnapshot("{}").leaseSnapshot("{\"durationMonths\":3}").financialSnapshot("{}").chargesSnapshot("[]").policiesSnapshot("{\"depositRefundDays\":15}").build());c.setCurrentRevisionId(rev.getId());contracts.saveAndFlush(c);
            var paid=payments.saveAndFlush(PaymentRequest.builder().rentalRequestId(r.getId()).listingId(l.getId()).contractId(c.getId()).payerId("tenant").payeeId("owner").type(PaymentType.RESERVATION_DEPOSIT).direction(PaymentDirection.TENANT_TO_LANDLORD).status(PaymentStatus.CONFIRMED).totalAmount(new BigDecimal("20000000")).confirmedAt(time.now()).transferReference("HS"+UUID.randomUUID().toString().substring(0,12)).build());
            deposits.saveAndFlush(DepositRecord.builder().rentalRequestId(r.getId()).contractId(c.getId()).initialPaymentRequestId(paid.getId()).landlordId("owner").tenantId("tenant").originalAmount(new BigDecimal("20000000")).heldAmount(new BigDecimal("20000000")).refundableAmount(new BigDecimal("20000000")).status(DepositStatus.HELD).build());
            if(!before)for(int n=0;n<3;n++)invoices.saveAndFlush(MonthlyInvoice.builder().contractId(c.getId()).periodIndex(n).periodStart(r.getMoveInDate().plusMonths(n)).periodEndExclusive(r.getMoveInDate().plusMonths(n+1)).status(n==2?MonthlyInvoiceStatus.DRAFT:MonthlyInvoiceStatus.PAID).totalAmount(BigDecimal.ZERO).build());
            return Map.of("rentalId",r.getId(),"contractId",c.getId());
        }
        @PostMapping("/test-harness/evidence/{rental}") @Transactional Map<String,String> evidence(@PathVariable String rental){String id=UUID.randomUUID().toString();files.saveAndFlush(com.hs.storage.model.StorageObject.builder().id(id).ownerId("owner").originalName("anh-hien-trang.png").objectKey("test/"+id).bucketName("test").contentType("image/png").sizeBytes(128L).referenceType("RENTAL_COMPLAINT_EVIDENCE").referenceId(rental).purpose(com.hs.storage.model.constant.StoragePurpose.GENERAL).visibility(com.hs.storage.model.constant.StorageVisibility.PRIVATE).status(com.hs.storage.model.constant.StorageStatus.READY).build());return Map.of("id",id);}
        @PostMapping("/test-harness/refund/{id}/receipt") @Transactional void refund(@PathVariable String id){var p=payments.findById(id).orElseThrow();if(!UserContextHolder.get().userId().equals(p.getPayerId()))throw new IllegalArgumentException("Only refund payer may report the fixture transfer");p.setStatus(PaymentStatus.TRANSFER_REPORTED);payments.saveAndFlush(p);paymentService.confirmReceipt(id,p.getPayeeId());}
        @PostMapping("/test-harness/clock") Map<String,Object> clock(@RequestBody Map<String,String> p){time.instant=Instant.parse(p.get("instant"));return Map.of("now",time.now());}
        @PostMapping("/test-harness/paid-bills/{contract}") @Transactional void paidBills(@PathVariable String contract){for(var b:invoices.findByContractIdOrderByPeriodIndexDesc(contract)){b.setStatus(MonthlyInvoiceStatus.PAID);invoices.save(b);}}
    }
}
