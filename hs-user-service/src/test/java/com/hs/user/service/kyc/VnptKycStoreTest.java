package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.hs.user.model.*;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.impl.CccdClaimService;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnptKycStoreTest {
    UserRepository users=mock(UserRepository.class);
    KycVerificationRepository verifications=mock(KycVerificationRepository.class);
    UserKycDetailsRepository details=mock(UserKycDetailsRepository.class);
    VnptKycStepRepository steps=mock(VnptKycStepRepository.class);
    CccdClaimService claims=mock(CccdClaimService.class);
    ObjectMapper mapper=new ObjectMapper();
    VnptKycStore store=new VnptKycStore(users,verifications,details,steps,new VnptKycProperties(true,"https://example.test","test","test",15,5,1),claims,mapper);
    KycVerification attempt;
    @BeforeEach void setup() {
        attempt=KycVerification.builder().id("attempt").userId("alice").provider(KycProvider.VNPT).status(KycStatus.PENDING).expiresAt(Instant.now().plusSeconds(900)).build();
        when(verifications.lockById("attempt")).thenReturn(Optional.of(attempt));
    }
    @Test void ownershipExpiryAndCancelBlockAllEvidenceAndClaims() {
        assertThrows(AppException.class,()->store.finalizeAttempt("bob","attempt"));
        attempt.setExpiresAt(Instant.now().minusSeconds(1));assertThrows(AppException.class,()->store.finalizeAttempt("alice","attempt"));
        attempt.setExpiresAt(Instant.now().plusSeconds(60));attempt.setStatus(KycStatus.EXPIRED);assertThrows(AppException.class,()->store.finalizeAttempt("alice","attempt"));
        verifyNoInteractions(steps);verify(claims,never()).claimInCurrentTransaction(anyString(),anyString());
    }
    @Test void verifiedFinalizeIsIdempotent() {
        attempt.setStatus(KycStatus.VERIFIED);assertEquals(KycStatus.VERIFIED,store.finalizeAttempt("alice","attempt"));verifyNoInteractions(steps);verify(claims,never()).claimInCurrentTransaction(anyString(),anyString());
    }
    @Test void retiredPendingAttemptDoesNotBlockVnptAndItsHistoryIsKept() {
        User user=new User();user.setId("alice");user.setActive(true);
        when(users.findByIdForUpdate("alice")).thenReturn(Optional.of(user));
        var legacy=KycVerification.builder().id("legacy").userId("alice").provider(KycProvider.DIDIT).status(KycStatus.PENDING).sessionUrl("https://example.test").build();
        when(verifications.findByUserIdAndStatusInOrderByCreatedAtDesc(eq("alice"),any())).thenReturn(List.of(legacy));
        store.start("alice");
        assertEquals(KycStatus.EXPIRED,legacy.getStatus());assertNull(legacy.getSessionUrl());
        verify(verifications).save(legacy);
        verify(verifications).save(argThat(v->v.getProvider()==KycProvider.VNPT && v.getStatus()==KycStatus.PENDING));
        verify(verifications,never()).delete(any());
    }
    @Test void activeVnptSessionIsReusedAndLegacyVerifiedIdentityCannotStartAgain() {
        User user=new User();user.setId("alice");user.setActive(true);
        when(users.findByIdForUpdate("alice")).thenReturn(Optional.of(user));
        when(verifications.findByUserIdAndStatusInOrderByCreatedAtDesc(eq("alice"),any())).thenReturn(List.of(attempt));
        assertEquals("attempt",store.start("alice").sessionId());verify(verifications,never()).save(any());
        var legacy=KycVerification.builder().provider(KycProvider.DIDIT).status(KycStatus.VERIFIED).build();
        when(verifications.findFirstByUserIdAndStatusOrderByVerifiedAtDesc("alice",KycStatus.VERIFIED)).thenReturn(Optional.of(legacy));
        assertThrows(AppException.class,()->store.start("alice"));verify(verifications,never()).save(any());
    }
    @Test void temporaryEvidenceIsPurgedEvenWhenProviderIsDisabled() {
        var disabledStore=new VnptKycStore(users,verifications,details,steps,new VnptKycProperties(false,"https://example.test","","",15,5,1),claims,mapper);
        Instant before=Instant.now().minusSeconds(86400);
        disabledStore.purgeTemporaryEvidence();
        Instant after=Instant.now().minusSeconds(86400);
        verify(steps).deleteByCreatedAtBefore(argThat(cutoff -> !cutoff.isBefore(before) && !cutoff.isAfter(after)));
    }
    @Test void foreignUploadHashesAndInFlightReplaysAreRejected() {
        VnptKycStep call=new VnptKycStep();call.setId("call");
        assertThrows(AppException.class,()->store.prepare("alice","attempt",call,List.of("foreign")));
        call.setState("RUNNING");when(steps.findById("call")).thenReturn(Optional.of(call));
        assertThrows(AppException.class,()->store.prepare("alice","attempt",call,List.of()));verify(steps,never()).saveAndFlush(any());
    }
    @Test void quotaStopsNewProviderCalls() {
        VnptKycStep call=new VnptKycStep();call.setId("call");when(steps.countByAttemptId("attempt")).thenReturn(30L);
        assertThrows(AppException.class,()->store.prepare("alice","attempt",call,List.of()));verify(steps,never()).saveAndFlush(any());
    }
    @Test void faceScanMustReferenceACompletedScanUploadFromTheSameAttempt() {
        VnptKycStep call=new VnptKycStep();call.setId("face-call");call.setKind("face");
        assertThrows(AppException.class,()->store.prepare("alice","attempt",call,List.of("face-hash","face-hash","scan-hash")));
        when(steps.existsByAttemptIdAndUploadHashAndStateAndKind("attempt","face-hash","DONE","upload")).thenReturn(true);
        assertThrows(AppException.class,()->store.prepare("alice","attempt",call,List.of("face-hash","face-hash","scan-hash")));
        when(steps.existsByAttemptIdAndUploadHashAndStateAndKind("attempt","scan-hash","DONE","scan3d")).thenReturn(true);
        store.prepare("alice","attempt",call,List.of("face-hash","face-hash","scan-hash"));
        verify(steps,atLeastOnce()).existsByAttemptIdAndUploadHashAndStateAndKind("attempt","face-hash","DONE","upload");
        verify(steps,atLeastOnce()).existsByAttemptIdAndUploadHashAndStateAndKind("attempt","scan-hash","DONE","scan3d");
        verify(steps).saveAndFlush(call);
    }
    @Test void coherentEvidenceClaimsLeadingZeroIdOnce() throws Exception {
        when(steps.findByAttemptIdOrderByCreatedAtAsc("attempt")).thenReturn(evidence());when(claims.claimInCurrentTransaction("alice","001234567890")).thenReturn(true);
        assertEquals(KycStatus.VERIFIED,store.finalizeAttempt("alice","attempt"));assertNotNull(attempt.getVerifiedAt());
        verify(details).save(argThat(row -> "alice".equals(row.getUserId()) && "001234567890".equals(row.getCccd())));
        var order=inOrder(claims,verifications);order.verify(claims).lockVerification("alice");order.verify(verifications).lockById("attempt");
        assertEquals(KycStatus.VERIFIED,store.finalizeAttempt("alice","attempt"));verify(claims,times(1)).claimInCurrentTransaction("alice","001234567890");
    }
    @Test void failureOrDuplicateIdNeverVerifies() {
        var proof=evidence();proof.stream().filter(x->x.getKind().equals("compare")).findFirst().orElseThrow().setResultJson("{\"message\":\"IDG-00000000\",\"object\":{\"msg\":\"NOMATCH\"}}");
        when(steps.findByAttemptIdOrderByCreatedAtAsc("attempt")).thenReturn(proof);
        assertEquals(KycStatus.REJECTED,store.finalizeAttempt("alice","attempt"));verify(claims,never()).claimInCurrentTransaction(anyString(),anyString());
        attempt.setStatus(KycStatus.PENDING);when(steps.findByAttemptIdOrderByCreatedAtAsc("attempt")).thenReturn(evidence());
        assertEquals(KycStatus.REJECTED,store.finalizeAttempt("alice","attempt"));assertNull(attempt.getVerifiedAt());
        verifyNoInteractions(details);
    }
    @Test void changedImagesIncompleteStepsAndMixedFaceEvidenceCannotFinalize() {
        for(String scenario:List.of("mixed","running","replacement","missing")) {
            var proof=evidence();
            if(scenario.equals("mixed")) {
                var face=proof.stream().filter(x->x.getKind().equals("face")).findFirst().orElseThrow();
                face.setNearDigest("other");face.setFarDigest("other");
            }
            if(scenario.equals("running"))proof.get(0).setState("RUNNING");
            if(scenario.equals("replacement"))proof.add(row("upload",null,null,null,"replacement","new",null,null,"{}"));
            if(scenario.equals("missing"))proof.removeIf(x->x.getKind().equals("card"));
            when(steps.findByAttemptIdOrderByCreatedAtAsc("attempt")).thenReturn(proof);
            assertThrows(AppException.class,()->store.finalizeAttempt("alice","attempt"),scenario);
        }
        verify(claims,never()).claimInCurrentTransaction(anyString(),anyString());
    }
    private List<VnptKycStep> evidence() {
        List<VnptKycStep> all=new ArrayList<>();
        all.add(row("upload",null,null,null,"front","f",null,null,"{}"));all.add(row("upload",null,null,null,"back","b",null,null,"{}"));all.add(row("upload",null,null,null,"selfie","near",null,null,"{}"));
        all.add(row("ocr","front","back",null,null,null,null,null,"{\"id\":\"001234567890\",\"msg_front\":\"OK\",\"msg_back\":\"OK\",\"expire_warning\":\"no\",\"match_front_back\":{\"match_id\":\"yes\",\"match_name\":\"yes\",\"match_bod\":\"yes\",\"match_sex\":\"yes\"},\"card_type\":\"CĂN CƯỚC\",\"checking_result_front\":{\"corner_cut_result\":\"0\",\"edited_result\":\"0\",\"recaptured_result\":\"0\",\"check_photocopied_result\":\"0\"}}"));
        all.add(row("card","front",null,null,null,null,null,null,"{\"liveness\":\"success\"}"));all.add(row("card","back",null,null,null,null,null,null,"{\"liveness\":\"success\"}"));
        String selfieReferenceDigest=VnptKycProxy.digest("selfie".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        all.add(row("compare","front",null,"selfie",null,null,null,null,"{\"msg\":\"MATCH\"}"));all.add(row("face",null,null,null,null,null,selfieReferenceDigest,selfieReferenceDigest,"{\"liveness\":\"success\"}"));all.add(row("mask",null,null,"selfie",null,null,null,null,"{\"masked\":\"no\"}"));return all;
    }
    private VnptKycStep row(String kind,String front,String back,String face,String hash,String digest,String near,String far,String object) {
        VnptKycStep s=new VnptKycStep();s.setKind(kind);s.setState("DONE");s.setFrontHash(front);s.setBackHash(back);s.setFaceHash(face);s.setUploadHash(hash);s.setImageDigest(digest);s.setNearDigest(near);s.setFarDigest(far);s.setResultJson("{\"message\":\"IDG-00000000\",\"object\":"+object+"}");return s;
    }
}
