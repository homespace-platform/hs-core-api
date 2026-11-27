package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.config.vnpt.*;
import com.hs.user.model.*;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.impl.CccdClaimService;
import com.hs.user.service.impl.KycServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="HS_KYC_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/core_kyc_test_[a-z0-9]+")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=VnptKycPostgresTest.Config.class)
@TestPropertySource(properties={"spring.datasource.url=${HS_KYC_TEST_PG_URL}","spring.datasource.username=${HS_KYC_TEST_PG_USER}","spring.datasource.password=${HS_KYC_TEST_PG_PASSWORD}","spring.jpa.hibernate.ddl-auto=create-drop","spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '5s'"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({VnptKycStore.class,CccdClaimService.class,KycServiceImpl.class})
class VnptKycPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs.user.model") @EnableJpaRepositories(basePackageClasses=UserRepository.class) @EnableJpaAuditing
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean VnptKycProperties properties(){return new VnptKycProperties(true,"https://example.test","test","test",15,5,1);}
    }
    @Autowired UserRepository users;
    @Autowired KycVerificationRepository verifications;
    @Autowired UserKycDetailsRepository details;
    @Autowired KycProviderStepRepository steps;
    @Autowired VnptKycStore store;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.hs.user.notification.AccountNotificationWriter notifications;
    @Autowired CccdClaimService claims;
    @Autowired KycServiceImpl kyc;
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void reset(){com.hs.common.security.SecretCrypto.configure("pg-test-secret");steps.deleteAllInBatch();details.deleteAllInBatch();verifications.deleteAllInBatch();users.deleteAllInBatch();jdbc.execute("ALTER TABLE users DROP COLUMN IF EXISTS cccd");}
    void user(String id){User u=new User();u.setId(id);u.setUsername(id);u.setEmail(id+"@example.invalid");users.saveAndFlush(u);}
    @Test void expiryNoticeQuerySuppressesOlderAttemptsAndExplicitlyCancelledSessions() {
        user("alice");var now=Instant.now();var page=org.springframework.data.domain.PageRequest.of(0,100);
        var old=KycVerification.builder().id("old").userId("alice").provider(KycProvider.VNPT).providerSessionId("old")
            .status(KycStatus.PENDING).expiresAt(now.minusSeconds(1)).build();verifications.saveAndFlush(old);
        assertEquals(List.of("old"),verifications.findExpiredAttemptIdsNeedingNotice(now,page));
        var latest=KycVerification.builder().id("latest").userId("alice").provider(KycProvider.VNPT).providerSessionId("latest")
            .status(KycStatus.EXPIRED).expiresAt(now.minusSeconds(1)).build();verifications.saveAndFlush(latest);
        jdbc.update("update kyc_verifications set created_at = ? where id = 'old'",java.sql.Timestamp.from(now.minusSeconds(100)));
        jdbc.update("update kyc_verifications set created_at = ? where id = 'latest'",java.sql.Timestamp.from(now));
        assertTrue(verifications.findExpiredAttemptIdsNeedingNotice(now,page).isEmpty());
        latest.setStatus(KycStatus.PENDING);verifications.saveAndFlush(latest);
        assertEquals(List.of("latest"),verifications.findExpiredAttemptIdsNeedingNotice(now,page));
        var account=users.findById("alice").orElseThrow();account.setActive(false);users.saveAndFlush(account);
        assertTrue(verifications.findExpiredAttemptIdsNeedingNotice(now,page).isEmpty());
        account.setActive(true);users.saveAndFlush(account);
        assertEquals(List.of("latest"),verifications.findExpiredAttemptIdsNeedingNotice(now,page));
        latest.setRetryNoticeSentAt(now);verifications.saveAndFlush(latest);assertTrue(verifications.findExpiredAttemptIdsNeedingNotice(now,page).isEmpty());
    }
    @Test void concurrentStartsReuseOneOwnedAttemptAndProviderSurvivesReload() throws Exception {
        user("alice");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<VnptKycStore.Session> action=()->{assertTrue(start.await(5,TimeUnit.SECONDS));return store.start("alice");};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertEquals(a.get(15,TimeUnit.SECONDS).sessionId(),b.get(15,TimeUnit.SECONDS).sessionId());
        }
        assertEquals(1,verifications.count());assertEquals(KycProvider.VNPT,verifications.findAll().get(0).getProvider());
    }
    @Test void concurrentCitizenClaimsHaveExactlyOneWinner() throws Exception {
        user("alice");user("bob");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return new TransactionTemplate(manager).execute(tx->claims.claimInCurrentTransaction("alice","001234567890"));});
            var b=pool.submit(()->{start.await();return new TransactionTemplate(manager).execute(tx->claims.claimInCurrentTransaction("bob","001234567890"));});start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from user_kyc_details where cccd_hash is not null and user_id in ('alice','bob')",Integer.class));
    }
    @Test void cancelBlocksLateProviderResultAndDuplicateReservationCannotSpendAgain() {
        user("alice");String id=store.start("alice").sessionId();
        KycProviderStep call=new KycProviderStep();call.setId("call");call.setKind("upload");
        store.prepare("alice",id,call,List.of());
        assertThrows(AppException.class,()->store.prepare("alice",id,call,List.of()));
        new TransactionTemplate(manager).executeWithoutResult(tx->{var v=verifications.lockById(id).orElseThrow();v.setStatus(KycStatus.EXPIRED);verifications.save(v);});
        assertThrows(AppException.class,()->store.finish("alice",call,"{}","hash"));
        assertEquals(KycStatus.EXPIRED,verifications.findById(id).orElseThrow().getStatus());assertNull(users.findById("alice").orElseThrow().getCccd());
    }
    @Test void oldDiditCheckExpandsWithoutDeletingHistoryAndMigrationIsIdempotent() {
        user("alice");
        jdbc.execute("ALTER TABLE kyc_verifications DROP CONSTRAINT IF EXISTS kyc_verifications_provider_check");
        jdbc.execute("ALTER TABLE kyc_verifications ADD CONSTRAINT kyc_verifications_provider_check CHECK (provider IN ('DIDIT'))");
        var old=KycVerification.builder().id("old").userId("alice").provider(KycProvider.DIDIT).providerSessionId("old-session").status(KycStatus.PENDING).build();verifications.saveAndFlush(old);
        var migration=new KycProviderSchemaMigration(jdbc);
        new TransactionTemplate(manager).executeWithoutResult(tx->migration.migrate());
        new TransactionTemplate(manager).executeWithoutResult(tx->migration.migrate());
        var vnpt=KycVerification.builder().id("new").userId("alice").provider(KycProvider.VNPT).providerSessionId("new").status(KycStatus.PENDING).expiresAt(Instant.now().plusSeconds(60)).build();verifications.saveAndFlush(vnpt);
        assertEquals(2,verifications.count());assertEquals(KycProvider.DIDIT,verifications.findById(old.getId()).orElseThrow().getProvider());
    }

    @Test void verifiedDetailsSurviveTemporaryEvidencePurge() throws Exception {
        user("alice");String id=store.start("alice").sessionId();proof(id);
        assertEquals(1,jdbc.queryForObject("select count(*) from pg_constraint where conname = 'fk_user_kyc_details_user'",Integer.class));
        var ocr=steps.findByProviderAndAttemptIdOrderByCreatedAtAsc(KycProvider.VNPT,id).stream().filter(s->"ocr".equals(s.getKind())).findFirst().orElseThrow();
        var response=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(ocr.getResultJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("object")).put("name","NGUYEN VAN A");
        ocr.setResultJson(response.toString());steps.saveAndFlush(ocr);
        assertEquals(KycStatus.VERIFIED,store.finalizeAttempt("alice",id));
        steps.deleteAllInBatch();entityManager.clear();
        assertEquals(1,details.count());
        assertEquals("NGUYEN VAN A",kyc.getVerifiedDetails("alice").fullName());
        assertEquals("001234567890",kyc.getVerifiedDetails("alice").cccd());
    }

    @Test void detailsWriteFailureRollsBackCitizenClaimAndVerification() {
        user("alice");String id=store.start("alice").sessionId();proof(id);
        jdbc.execute("ALTER TABLE user_kyc_details ADD CONSTRAINT test_details_failure CHECK (cccd_hash IS NULL)");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->store.finalizeAttempt("alice",id));
            assertNull(users.findById("alice").orElseThrow().getCccd());
            assertEquals(KycStatus.PENDING,verifications.findById(id).orElseThrow().getStatus());
            assertEquals(0,details.count());
        } finally {jdbc.execute("ALTER TABLE user_kyc_details DROP CONSTRAINT test_details_failure");}
    }
    @Test void legacyColumnMigratesWithoutOverwritingKycAndIsRemovedIdempotently() {
        user("alice");user("bob");
        jdbc.execute("ALTER TABLE users ADD COLUMN cccd varchar(20)");
        jdbc.update("UPDATE users SET cccd = ? WHERE id = ?", "001234567890", "alice");
        jdbc.update("UPDATE users SET cccd = ? WHERE id = ?", "009999999999", "bob");
        var row = new UserKycDetails();row.setUserId("bob");row.setCccd("008888888888");row.setFullName("Existing verified name");details.saveAndFlush(row);
        var migration = new com.hs.user.config.database.CccdUniquenessInitializer(jdbc, manager);
        migration.run();migration.run();
        assertEquals("001234567890", details.findById("alice").orElseThrow().getCccd());
        assertEquals("008888888888", details.findById("bob").orElseThrow().getCccd());
        assertEquals("Existing verified name", details.findById("bob").orElseThrow().getFullName());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'users' AND column_name = 'cccd'", Integer.class));
        // User consumers read the projection from KYC even after the legacy column is gone.
        assertEquals("001234567890", users.findById("alice").orElseThrow().getCccd());
        new TransactionTemplate(manager).executeWithoutResult(tx ->
                assertEquals("001234567890", users.lockMembershipOwner("alice").orElseThrow().getCccd()));
    }

    @Test void conflictingLegacyCitizensAbortMigrationWithoutDroppingData() {
        user("alice");user("bob");
        jdbc.execute("ALTER TABLE users ADD COLUMN cccd varchar(20)");
        jdbc.update("UPDATE users SET cccd = ?", "001234567890");
        assertThrows(IllegalStateException.class, () -> new com.hs.user.config.database.CccdUniquenessInitializer(jdbc, manager).run());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users WHERE cccd IS NOT NULL", Integer.class));
        assertEquals(0, details.count());
    }

    @Test void cccdIsEncryptedAtRestAndHashIsUniqueAcrossUsers() {
        user("alice");user("bob");
        var a=new UserKycDetails();a.setUserId("alice");a.setCccd("001234567890");a.setFullName("Nguyen A");details.saveAndFlush(a);
        assertTrue(jdbc.queryForObject("SELECT cccd FROM user_kyc_details WHERE user_id='alice'",String.class).startsWith("v1:"));
        assertTrue(jdbc.queryForObject("SELECT full_name FROM user_kyc_details WHERE user_id='alice'",String.class).startsWith("v1:"));
        entityManager.clear();
        assertEquals("001234567890",details.findById("alice").orElseThrow().getCccd());
        assertEquals("001234567890",users.findById("alice").orElseThrow().getCccd());
        var b=new UserKycDetails();b.setUserId("bob");b.setCccd(" 001234567890");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->details.saveAndFlush(b));
    }

    @Test void legacyPlaintextRowsAreEncryptedIdempotently() {
        user("alice");
        jdbc.update("INSERT INTO user_kyc_details (user_id, cccd, full_name) VALUES ('alice','001234567890','Nguyen A')");
        var init=new com.hs.user.config.database.CccdUniquenessInitializer(jdbc, manager);
        var job=new com.hs.user.config.database.KycEncryptionMigration(jdbc, manager);
        init.run();job.run();
        String once=jdbc.queryForObject("SELECT cccd FROM user_kyc_details WHERE user_id='alice'",String.class);
        init.run();job.run();
        assertEquals(once,jdbc.queryForObject("SELECT cccd FROM user_kyc_details WHERE user_id='alice'",String.class));
        assertTrue(once.startsWith("v1:"));
        assertNotNull(jdbc.queryForObject("SELECT cccd_hash FROM user_kyc_details WHERE user_id='alice'",String.class));
        entityManager.clear();
        assertEquals("Nguyen A",details.findById("alice").orElseThrow().getFullName());
    }

    private void proof(String attempt) {
        evidence(attempt,"upload","front",null,null,null,null,null,"{}");
        evidence(attempt,"upload","back",null,null,null,null,null,"{}");
        evidence(attempt,"upload","selfie",null,null,null,null,null,"{}");
        evidence(attempt,"card",null,"front",null,null,null,null,"{\"liveness\":\"success\"}");
        evidence(attempt,"card",null,"back",null,null,null,null,"{\"liveness\":\"success\"}");
        evidence(attempt,"ocr",null,"front","back",null,null,null,"{\"id\":\"001234567890\",\"msg_front\":\"OK\",\"msg_back\":\"OK\",\"expire_warning\":\"no\",\"match_front_back\":{\"match_id\":\"yes\",\"match_name\":\"yes\",\"match_bod\":\"yes\",\"match_sex\":\"yes\"},\"card_type\":\"CĂN CƯỚC\",\"checking_result_front\":{\"corner_cut_result\":\"0\",\"edited_result\":\"0\",\"recaptured_result\":\"0\",\"check_photocopied_result\":\"0\"}}");
        evidence(attempt,"compare",null,"front",null,"selfie",null,null,"{\"msg\":\"MATCH\"}");
        String digest=VnptKycProxy.digest("selfie".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        evidence(attempt,"face",null,null,null,null,digest,digest,"{\"liveness\":\"success\"}");
        evidence(attempt,"mask",null,null,null,"selfie",null,null,"{\"masked\":\"no\"}");
    }
    private void evidence(String attempt,String kind,String upload,String front,String back,String face,String near,String far,String object) {
        var row=new KycProviderStep();row.setProvider(KycProvider.VNPT);row.setId(UUID.randomUUID().toString());row.setAttemptId(attempt);row.setKind(kind);row.setState("DONE");row.setCreatedAt(Instant.now());
        row.setUploadHash(upload);row.setImageDigest("fixture");row.setFrontHash(front);row.setBackHash(back);row.setFaceHash(face);row.setNearDigest(near);row.setFarDigest(far);
        row.setResultJson("{\"message\":\"IDG-00000000\",\"object\":"+object+"}");steps.saveAndFlush(row);
    }
}
