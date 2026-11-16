package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.config.vnpt.*;
import com.hs.user.model.*;
import com.hs.user.model.Role;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.impl.CccdClaimService;
import com.hs.user.service.impl.KycServiceImpl;
import com.hs.user.utils.CurrentUserUtils;
import com.hs.user.constant.RoleConstants;
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
@Import({VnptKycStore.class,CccdClaimService.class,AdminKycService.class,KycServiceImpl.class})
class VnptKycPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs.user.model") @EnableJpaRepositories(basePackageClasses=UserRepository.class) @EnableJpaAuditing
    static class Config {
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean VnptKycProperties properties(){return new VnptKycProperties(true,"https://example.test","test","test",15,5,1);}
        @Bean CurrentUserUtils actor(){
            var actor=org.mockito.Mockito.mock(CurrentUserUtils.class);
            var admin=new User();admin.setId("operator");var role=new Role();role.setName(RoleConstants.ADMIN);admin.setRole(role);
            org.mockito.Mockito.when(actor.getCurrentUser()).thenReturn(admin);return actor;
        }
    }
    @Autowired UserRepository users;
    @Autowired KycVerificationRepository verifications;
    @Autowired VnptKycStepRepository steps;
    @Autowired VnptKycStore store;
    @Autowired CccdClaimService claims;
    @Autowired AdminKycService adminKyc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @BeforeEach void reset(){steps.deleteAllInBatch();verifications.deleteAllInBatch();users.deleteAllInBatch();jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_test_cccd ON users(cccd)");}
    void user(String id){User u=new User();u.setId(id);u.setUsername(id);u.setEmail(id+"@example.invalid");users.saveAndFlush(u);}
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
            var a=pool.submit(()->{start.await();return claims.tryClaim("alice","001234567890");});
            var b=pool.submit(()->{start.await();return claims.tryClaim("bob","001234567890");});start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,jdbc.queryForObject("select count(*) from users where cccd = '001234567890'",Integer.class));
    }
    @Test void cancelBlocksLateProviderResultAndDuplicateReservationCannotSpendAgain() {
        user("alice");String id=store.start("alice").sessionId();
        VnptKycStep call=new VnptKycStep();call.setId("call");call.setKind("upload");
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

    @Test void concurrentManualClaimsHaveOneWinnerAndOneVerifiedRecord() throws Exception {
        user("alice");user("bob");var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return manualVerify("alice","001234567890");});
            var b=pool.submit(()->{start.await();return manualVerify("bob","001234567890");});start.countDown();
            assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,verifications.findAll().stream().filter(v->v.getStatus()==KycStatus.VERIFIED).count());
        assertEquals(1,jdbc.queryForObject("select count(*) from users where cccd = '001234567890'",Integer.class));
    }

    @Test void failedManualRecordRollsBackTheCitizenClaimAndPendingSessionChanges() {
        user("alice");String id=store.start("alice").sessionId();
        jdbc.execute("ALTER TABLE kyc_verifications ADD CONSTRAINT test_manual_failure CHECK (provider <> 'ADMIN')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->adminKyc.verify("alice","001234567890"));
            assertNull(users.findById("alice").orElseThrow().getCccd());
            assertEquals(KycStatus.PENDING,verifications.findById(id).orElseThrow().getStatus());
        } finally {jdbc.execute("ALTER TABLE kyc_verifications DROP CONSTRAINT test_manual_failure");}
    }

    @Test void concurrentManualAndVnptVerificationCannotOverwriteEachOther() throws Exception {
        user("alice");String id=store.start("alice").sessionId();proof(id);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var manual=pool.submit(()->{start.await();return manualVerify("alice","001234567899");});
            var vnpt=pool.submit(()->{start.await();try{return store.finalizeAttempt("alice",id)==KycStatus.VERIFIED;}catch(AppException ex){return false;}});
            start.countDown();assertNotEquals(manual.get(15,TimeUnit.SECONDS),vnpt.get(15,TimeUnit.SECONDS));
        }
        var verified=verifications.findAll().stream().filter(v->v.getStatus()==KycStatus.VERIFIED).toList();
        assertEquals(1,verified.size());
        assertEquals(verified.get(0).getProvider()==KycProvider.ADMIN?"001234567899":"001234567890",users.findById("alice").orElseThrow().getCccd());
    }

    private boolean manualVerify(String userId,String cccd) {
        try{return adminKyc.verify(userId,cccd).status()==KycStatus.VERIFIED;}catch(AppException ex){return false;}
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
        var row=new VnptKycStep();row.setId(UUID.randomUUID().toString());row.setAttemptId(attempt);row.setKind(kind);row.setState("DONE");row.setCreatedAt(Instant.now());
        row.setUploadHash(upload);row.setImageDigest("fixture");row.setFrontHash(front);row.setBackHash(back);row.setFaceHash(face);row.setNearDigest(near);row.setFarDigest(far);
        row.setResultJson("{\"message\":\"IDG-00000000\",\"object\":"+object+"}");steps.saveAndFlush(row);
    }
}
