package com.hs.contract.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.common.time.BillingTime;
import com.hs.contract.model.*;
import com.hs.contract.model.constant.ContractStatus;
import com.hs.contract.repository.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.*;
import com.hs.payment.model.*;
import com.hs.payment.model.constant.*;
import com.hs.payment.receipt.*;
import com.hs.payment.repository.*;
import com.hs.payment.service.*;
import com.hs.payment.service.qr.VietQrProvider;
import com.hs.storage.dto.response.StorageObjectResponse;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.*;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.storage.service.StorageService;
import com.hs.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in, explicitly named disposable PostgreSQL only. No user DB, bank or object-storage calls.
 */
@EnabledIfEnvironmentVariable(
    named = "HS_RECEIPT_TEST_PG_URL",
    matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/hs_receipt_test")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PaymentReceiptPostgresTest.Config.class)
@TestPropertySource(
    properties = {
      "spring.datasource.url=${HS_RECEIPT_TEST_PG_URL}",
      "spring.datasource.username=postgres",
      "spring.datasource.password=hs_receipt_test",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.profiles.active=test"
    })
@Import({
  PaymentRequestService.class,
  PaymentReceiptService.class,
  PaymentReceiptPdfWorker.class,
  PaymentReceiptStorageAccess.class,
  ContractPaymentReceiptContext.class
})
class PaymentReceiptPostgresTest {
  @SpringBootConfiguration
  @EntityScan("com.hs")
  @EnableJpaAuditing
  @EnableJpaRepositories(
      basePackageClasses = {
        PaymentReceiptRepository.class,
        PaymentRequestRepository.class,
        ContractRepository.class,
        RentalRequestRepository.class,
        StorageObjectRepository.class,
        UserRepository.class
      })
  static class Config {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }

  @MockitoBean BankAccountService banks;
  @MockitoBean VietQrProvider qr;
  @MockitoBean TransferReferenceGenerator refs;
  @MockitoBean PaymentProofUploadSessionService proof;
  @MockitoBean BillingTime time;
  @MockitoBean PaymentReceiptPdfRenderer renderer;
  @MockitoBean StorageService storage;
  @Autowired PaymentRequestService payments;
  @Autowired PaymentReceiptService receipts;
  @Autowired PaymentReceiptPdfWorker worker;
  @Autowired PaymentReceiptStorageAccess storageAccess;
  @Autowired PaymentReceiptRepository receiptRepo;
  @Autowired PaymentRequestRepository paymentRepo;
  @Autowired PaymentLineItemRepository lineRepo;
  @Autowired DepositRecordRepository deposits;
  @Autowired ListingRepository listings;
  @Autowired RentalRequestRepository rentals;
  @Autowired ContractRepository contracts;
  @Autowired ContractRevisionRepository revisions;
  @Autowired StorageObjectRepository files;
  @Autowired PlatformTransactionManager transactions;
  String rentalId, listingId;
  final Instant now = Instant.parse("2026-11-07T02:00:00Z");

  void tx(Runnable action) {
    new TransactionTemplate(transactions).executeWithoutResult(t -> action.run());
  }

  <T> T read(java.util.function.Supplier<T> action) {
    return new TransactionTemplate(transactions).execute(t -> action.get());
  }

  @BeforeEach
  void fixture() {
    when(time.now()).thenReturn(now);
    var listing =
        listings.saveAndFlush(
            Listing.builder()
                .ownerId("owner")
                .title("Receipt integration test")
                .description("Fixture")
                .category(ListingCategory.ROOM)
                .status(ListingStatus.RESERVED)
                .priceAmount(new BigDecimal("2000000"))
                .areaM2(BigDecimal.TEN)
                .currency("VND")
                .priceUnit(PriceUnit.MONTH)
                .paymentCycle(PaymentCycle.MONTHLY)
                .depositType(DepositType.NONE)
                .availableFrom(LocalDate.of(2026, 11, 7))
                .minimumLeaseMonths(3)
                .build());
    listingId = listing.getId();
    var rental =
        rentals.saveAndFlush(
            RentalRequest.builder()
                .id(UUID.randomUUID().toString())
                .listing(listing)
                .ownerId("owner")
                .renterId("tenant")
                .renterName("Nguyễn Minh An")
                .renterPhone("0900000000")
                .status(RentalRequestStatus.ACCEPTED)
                .moveInDate(LocalDate.of(2026, 11, 7))
                .leaseMonths(3)
                .monthlyRentPrice(new BigDecimal("2000000"))
                .depositAmount(new BigDecimal("1000000"))
                .listingSnapshot(
                    "{\"fullAddress\":\"12 Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí"
                        + " Minh\",\"unitNumber\":\"P.302\"}")
                .build());
    rentalId = rental.getId();
    TestTransaction.flagForCommit();
    TestTransaction.end();
  }

  String payment(PaymentType type, PaymentDirection direction, String amount) {
    return read(
        () -> {
          var p =
              paymentRepo.saveAndFlush(
                  PaymentRequest.builder()
                      .rentalRequestId(rentalId)
                      .listingId(listingId)
                      .payerId(
                          direction == PaymentDirection.TENANT_TO_LANDLORD ? "tenant" : "owner")
                      .payeeId(
                          direction == PaymentDirection.TENANT_TO_LANDLORD ? "owner" : "tenant")
                      .type(type)
                      .direction(direction)
                      .status(PaymentStatus.TRANSFER_REPORTED)
                      .totalAmount(new BigDecimal(amount))
                      .transferReference(
                          "HS" + UUID.randomUUID().toString().replace("-", "").substring(0, 15))
                      .payerReportedAt(now.minusSeconds(50))
                      .payeeBankAccountSnapshot(
                          "{\"bankBin\":\"970422\",\"bankCode\":\"MB\",\"bankName\":\"Ngân hàng"
                              + " Quân"
                              + " Đội\",\"accountNumber\":\"123456\",\"accountHolderName\":\"NGUYEN"
                              + " MINH AN\"}")
                      .build());
          lineRepo.saveAndFlush(
              PaymentLineItem.builder()
                  .paymentRequestId(p.getId())
                  .type("PAYMENT")
                  .displayName("Tiền đã chuyển")
                  .amount(p.getTotalAmount())
                  .quantity(1)
                  .sortOrder(0)
                  .build());
          return p.getId();
        });
  }

  String confirm(String id) {
    tx(() -> payments.confirmReceipt(id, paymentRepo.findById(id).orElseThrow().getPayeeId()));
    return read(() -> receiptRepo.findByPaymentRequestId(id).orElseThrow().getId());
  }

  @ParameterizedTest
  @EnumSource(PaymentType.class)
  void everyPositivePaymentTypeCreatesExactlyOneReceipt(PaymentType type) {
    var reverse = type == PaymentType.DEPOSIT_REFUND || type == PaymentType.INITIAL_PAYMENT_REFUND;
    String id =
        payment(
            type,
            reverse ? PaymentDirection.LANDLORD_TO_TENANT : PaymentDirection.TENANT_TO_LANDLORD,
            "1000000");
    if (type == PaymentType.DEPOSIT_ADJUSTMENT)
      tx(
          () ->
              deposits.saveAndFlush(
                  DepositRecord.builder()
                      .rentalRequestId(rentalId)
                      .initialPaymentRequestId("original-deposit")
                      .landlordId("owner")
                      .tenantId("tenant")
                      .originalAmount(BigDecimal.ZERO)
                      .heldAmount(BigDecimal.ZERO)
                      .refundableAmount(BigDecimal.ZERO)
                      .status(DepositStatus.HELD)
                      .build()));
    String receipt = confirm(id);
    confirm(id);
    assertEquals(
        1,
        read(
            () ->
                receiptRepo.findAll().stream()
                    .filter(r -> id.equals(r.getPaymentRequestId()))
                    .count()));
    assertEquals("1000000.00", receipts.get(receipt, "tenant").totalAmount().toPlainString());
    assertEquals(reverse ? "REFUND" : "PAYMENT", receipts.get(receipt, "owner").kind());
    assertEquals(
        "Nguyễn Minh An",
        reverse
            ? receipts.get(receipt, "owner").payeeName()
            : receipts.get(receipt, "owner").payerName());
    assertEquals(
        "12 Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh",
        receipts.get(receipt, "owner").propertyAddress());
  }

  @Test
  void reportedTransfersAndPayerSelfConfirmationProduceNoReceipt() {
    String id = payment(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, "3000000");
    assertTrue(read(() -> receiptRepo.findByPaymentRequestId(id).isEmpty()));
    assertThrows(AppException.class, () -> tx(() -> payments.confirmReceipt(id, "tenant")));
    assertTrue(read(() -> receiptRepo.findByPaymentRequestId(id).isEmpty()));
    assertEquals(
        PaymentStatus.TRANSFER_REPORTED,
        read(() -> paymentRepo.findById(id).orElseThrow().getStatus()));
  }

  @Test
  void rollbackNeverLeavesAReceiptForAnUnconfirmedPayment() {
    String id = payment(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, "3000000");
    assertThrows(
        IllegalStateException.class,
        () ->
            tx(
                () -> {
                  payments.confirmReceipt(id, "owner");
                  throw new IllegalStateException("downstream failed");
                }));
    assertTrue(read(() -> receiptRepo.findByPaymentRequestId(id).isEmpty()));
    assertEquals(
        PaymentStatus.TRANSFER_REPORTED,
        read(() -> paymentRepo.findById(id).orElseThrow().getStatus()));
  }

  @Test
  void splitPaymentsAndOneCombinedRefundKeepTheirOwnImmutableHistory() {
    String deposit =
        payment(PaymentType.RESERVATION_DEPOSIT, PaymentDirection.TENANT_TO_LANDLORD, "1000000");
    String rent = payment(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, "2000000");
    String first = confirm(deposit), second = confirm(rent);
    String original = read(() -> receiptRepo.findById(first).orElseThrow().getSnapshotJson());
    String refund =
        payment(PaymentType.INITIAL_PAYMENT_REFUND, PaymentDirection.LANDLORD_TO_TENANT, "3000000");
    confirm(refund);
    assertNotEquals(first, second);
    assertEquals(original, read(() -> receiptRepo.findById(first).orElseThrow().getSnapshotJson()));
    assertEquals(3, receipts.list("tenant", "TENANT", null, rentalId, 1, 12).getTotalElements());
    assertEquals(0, receipts.list("stranger", null, null, rentalId, 1, 12).getTotalElements());
    assertThrows(AppException.class, () -> receipts.byPayment(deposit, "stranger"));
  }

  @Test
  void laterRentalEditsAndAttachingALeaseCannotRewriteTheOldPdfSnapshot() {
    String
        id =
            payment(
                PaymentType.RESERVATION_DEPOSIT, PaymentDirection.TENANT_TO_LANDLORD, "1000000"),
        receipt = confirm(id);
    var before = receipts.get(receipt, "owner");
    String contract =
        read(
            () -> {
              var c =
                  contracts.saveAndFlush(
                      Contract.builder()
                          .contractNumber("HD-" + UUID.randomUUID())
                          .rentalRequestId(rentalId)
                          .listingId(listingId)
                          .landlordId("owner")
                          .tenantId("tenant")
                          .templateId("t")
                          .templateVersionId("v")
                          .status(ContractStatus.DRAFT)
                          .build());
              var p = paymentRepo.findById(id).orElseThrow();
              p.setContractId(c.getId());
              paymentRepo.save(p);
              var r = rentals.findById(rentalId).orElseThrow();
              r.setRenterName("New name");
              r.setListingSnapshot("{}");
              rentals.save(r);
              return c.getId();
            });
    assertEquals(before.snapshotHash(), receipts.get(receipt, "owner").snapshotHash());
    assertEquals(before.propertyAddress(), receipts.get(receipt, "owner").propertyAddress());
    assertEquals(1, receipts.list("owner", "LANDLORD", contract, null, 1, 12).getTotalElements());
    assertEquals(0, receipts.list("owner", "TENANT", contract, null, 1, 12).getTotalElements());
  }

  @Test
  void converterFailureCannotUndoPaymentAndReadyPdfIsPrivateAndNotRegenerated() {
    String id = payment(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, "3000000"),
        receipt = confirm(id);
    when(renderer.render(any(), anyString()))
        .thenThrow(new IllegalStateException("converter offline"));
    worker.generate(receipt);
    assertEquals(
        PaymentStatus.CONFIRMED, read(() -> paymentRepo.findById(id).orElseThrow().getStatus()));
    assertEquals(PaymentReceipt.PdfStatus.FAILED, receipts.get(receipt, "tenant").pdfStatus());
    assertThrows(AppException.class, () -> receipts.pdf(receipt, "tenant"));
    tx(
        () -> {
          var r = receiptRepo.findById(receipt).orElseThrow();
          r.setNextAttemptAt(Instant.now().minusSeconds(1));
          receiptRepo.save(r);
        });
    receipts.retry(receipt, "owner");
    byte[] pdf = "%PDF-test-receipt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    doReturn(pdf).when(renderer).render(any(), anyString());
    when(storage.uploadDirect(
            any(),
            anyString(),
            eq("application/pdf"),
            eq(StoragePurpose.PAYMENT_RECEIPT),
            eq("PAYMENT_RECEIPT"),
            eq(receipt),
            eq(StorageVisibility.PRIVATE),
            eq("SYSTEM")))
        .thenAnswer(
            c -> {
              var f =
                  files.saveAndFlush(
                      StorageObject.builder()
                          .id(UUID.randomUUID().toString())
                          .objectKey("receipt/" + UUID.randomUUID())
                          .bucketName("test")
                          .originalName("receipt.pdf")
                          .contentType("application/pdf")
                          .sizeBytes((long) pdf.length)
                          .ownerId("SYSTEM")
                          .purpose(StoragePurpose.PAYMENT_RECEIPT)
                          .referenceType("PAYMENT_RECEIPT")
                          .referenceId(receipt)
                          .visibility(StorageVisibility.PRIVATE)
                          .status(StorageStatus.READY)
                          .build());
              return new StorageObjectResponse(
                  f.getId(),
                  f.getOriginalName(),
                  f.getContentType(),
                  f.getSizeBytes(),
                  null,
                  "pdf",
                  "SYSTEM",
                  "PAYMENT_RECEIPT",
                  receipt,
                  f.getPurpose(),
                  f.getVisibility(),
                  f.getStatus(),
                  null,
                  null);
            });
    worker.generate(receipt);
    worker.generate(receipt);
    assertEquals(PaymentReceipt.PdfStatus.READY, receipts.get(receipt, "tenant").pdfStatus());
    verify(storage, times(1))
        .uploadDirect(
            any(), anyString(), anyString(), any(), anyString(), anyString(), any(), anyString());
    var file =
        read(
            () ->
                files
                    .findById(receiptRepo.findById(receipt).orElseThrow().getStorageObjectId())
                    .orElseThrow());
    when(storage.downloadDirect(file.getId())).thenReturn(pdf);
    assertArrayEquals(pdf, receipts.pdf(receipt, "owner"));
    assertArrayEquals(pdf, receipts.pdf(receipt, "tenant"));
    assertThrows(AppException.class, () -> receipts.pdf(receipt, "stranger"));
    assertTrue(storageAccess.canAccess("tenant", file));
    assertFalse(storageAccess.canAccess("stranger", file));
    assertTrue(storageAccess.preventsDeletion(file));
    file.setReferenceId("other");
    assertFalse(storageAccess.canAccess("tenant", file));
  }

  @Test
  void concurrentConfirmationsCannotCreateDuplicateReceipts() throws Exception {
    String id = payment(PaymentType.INITIAL, PaymentDirection.TENANT_TO_LANDLORD, "3000000");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var a =
          pool.submit(
              () -> {
                start.await();
                confirm(id);
                return true;
              });
      var b =
          pool.submit(
              () -> {
                start.await();
                confirm(id);
                return true;
              });
      start.countDown();
      assertTrue(a.get(20, TimeUnit.SECONDS));
      assertTrue(b.get(20, TimeUnit.SECONDS));
    }
    assertEquals(
        1,
        read(
            () ->
                receiptRepo.findAll().stream()
                    .filter(r -> r.getPaymentRequestId().equals(id))
                    .count()));
  }
}
