package com.hs.payment.receipt;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.membership.MembershipKind;
import com.hs.listing.membership.MembershipTier;
import com.hs.payment.membership.MembershipOrder;
import com.hs.payment.membership.MembershipOrderRepository;
import com.hs.storage.repository.StorageObjectRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in: runs only against a disposable PostgreSQL named like core_notification_rollout_test_xxx. */
@EnabledIfEnvironmentVariable(
    named = "HS_TEST_PG_URL",
    matches = "jdbc:postgresql://[^/]+/core_notification_rollout_test_[A-Za-z0-9]+")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "spring.datasource.url=${HS_TEST_PG_URL}",
      "spring.datasource.username=${HS_TEST_PG_USER}",
      "spring.datasource.password=${HS_TEST_PG_PASSWORD}",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.open-in-view=false"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(MembershipReceiptService.class)
class MembershipReceiptPostgresTest {
  @SpringBootConfiguration
  @EntityScan("com.hs")
  @EnableJpaAuditing
  @EnableJpaRepositories(
      basePackageClasses = {
        MembershipReceiptRepository.class,
        MembershipOrderRepository.class,
        StorageObjectRepository.class,
        com.hs.user.repository.UserRepository.class
      })
  static class Config {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }

  @MockitoBean com.hs.storage.service.StorageService storage;
  @Autowired MembershipReceiptService service;
  @Autowired MembershipReceiptRepository receipts;
  @Autowired MembershipOrderRepository orders;
  @Autowired PlatformTransactionManager manager;

  TransactionTemplate tx() {
    return new TransactionTemplate(manager);
  }

  MembershipOrder order(String id, MembershipOrder.Status status, Instant paidAt) {
    var o = new MembershipOrder();
    o.setId(id);
    o.setOwnerId("owner-" + id);
    o.setKind(MembershipKind.PLAN);
    o.setTier(MembershipTier.STARTER);
    o.setAmountVnd(49_000);
    o.setStatus(status);
    o.setProvider(MembershipOrder.Provider.SEPAY);
    o.setCreatedAt(Instant.parse("2026-11-01T00:00:00Z"));
    o.setExpiresAt(Instant.parse("2026-11-01T00:15:00Z"));
    o.setPaidAt(paidAt);
    o.setTransactionNo("tx" + id);
    return orders.saveAndFlush(o);
  }

  @BeforeEach
  void clean() {
    receipts.deleteAllInBatch();
    orders.deleteAllInBatch();
  }

  @Test
  void concurrentConfirmationsOfOnePaidOrderCreateExactlyOneReceipt() throws Exception {
    order("c".repeat(32), MembershipOrder.Status.PAID, Instant.parse("2026-11-02T03:00:00Z"));
    var pool = Executors.newFixedThreadPool(4);
    var start = new CountDownLatch(1);
    List<Future<?>> done = new ArrayList<>();
    for (int i = 0; i < 4; i++)
      done.add(
          pool.submit(
              () -> {
                start.await();
                // Same ordering as production: the order row lock serialises the PAID transition.
                tx().executeWithoutResult(
                        t -> service.capture(orders.lockById("c".repeat(32)).orElseThrow()));
                return null;
              }));
    start.countDown();
    for (var f : done) f.get(20, TimeUnit.SECONDS);
    pool.shutdown();
    assertEquals(1, receipts.count());
    assertTrue(receipts.findByOrderId("c".repeat(32)).orElseThrow().getReceiptNumber().startsWith("HS-MB-"));
  }

  @Test
  void databaseRejectsASecondReceiptForTheSameOrder() {
    order("d".repeat(32), MembershipOrder.Status.PAID, Instant.parse("2026-11-02T03:00:00Z"));
    tx().executeWithoutResult(t -> service.capture(orders.findById("d".repeat(32)).orElseThrow()));
    var dup = receipts.findByOrderId("d".repeat(32)).orElseThrow();
    var copy = new MembershipReceipt();
    copy.setId(UUID.randomUUID().toString());
    copy.setOrderId(dup.getOrderId());
    copy.setOwnerId(dup.getOwnerId());
    copy.setReceiptNumber("HS-MB-20261102-OTHER");
    copy.setIssuedAt(dup.getIssuedAt());
    copy.setSnapshotJson(dup.getSnapshotJson());
    copy.setSnapshotHash(dup.getSnapshotHash());
    assertThrows(DataIntegrityViolationException.class, () -> receipts.saveAndFlush(copy));
  }

  @Test
  void backfillCreatesMissingReceiptsForPaidOrdersOnlyAndIsIdempotent() {
    var paid1 = Instant.parse("2026-10-02T03:00:00Z");
    var paid2 = Instant.parse("2026-10-03T03:00:00Z");
    order("e1".repeat(16), MembershipOrder.Status.PAID, paid1);
    order("e2".repeat(16), MembershipOrder.Status.PAID, paid2);
    order("e3".repeat(16), MembershipOrder.Status.PENDING, null);
    order("e4".repeat(16), MembershipOrder.Status.REVIEW, paid2);
    var job = new MembershipReceiptBackfill(service);
    assertEquals(2, job.run());
    assertEquals(2, receipts.count());
    assertEquals(paid1, receipts.findByOrderId("e1".repeat(16)).orElseThrow().getIssuedAt());
    assertEquals(paid2, receipts.findByOrderId("e2".repeat(16)).orElseThrow().getIssuedAt());
    assertEquals(0, job.run()); // second run examines nothing
    assertEquals(2, receipts.count());
  }
}
