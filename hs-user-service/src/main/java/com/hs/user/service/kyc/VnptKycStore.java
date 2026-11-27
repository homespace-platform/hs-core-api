package com.hs.user.service.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.response.KycVerifiedDetailsResponse;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.hs.user.integration.vnpt.VnptKycPolicy;
import com.hs.user.model.*;
import com.hs.user.model.constant.*;
import com.hs.user.repository.*;
import com.hs.user.service.impl.CccdClaimService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class VnptKycStore {
    private final UserRepository users;
    private final KycVerificationRepository verifications;
    private final UserKycDetailsRepository detailsRepository;
    private final KycProviderStepRepository steps;
    private final VnptKycProperties config;
    private final CccdClaimService claims;
    private final ObjectMapper mapper;
    private final com.hs.user.notification.AccountNotificationWriter notifications;

    @Transactional
    public Session start(String userId) {
        if (!config.configured()) throw new AppException(UserErrorCode.KYC_NOT_CONFIGURED);
        users.findByIdForUpdate(userId).filter(u -> Boolean.TRUE.equals(u.getActive()))
            .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));
        if (verifications.findFirstByUserIdAndStatusOrderByVerifiedAtDesc(userId,KycStatus.VERIFIED).isPresent())
            throw new AppException(UserErrorCode.KYC_ALREADY_VERIFIED);
        for (KycVerification v:verifications.findByUserIdAndStatusInOrderByCreatedAtDesc(userId,List.of(KycStatus.PENDING,KycStatus.REVIEW_REQUIRED))) {
            if(v.getProvider()!=KycProvider.VNPT) {
                v.setStatus(KycStatus.EXPIRED);v.setSessionUrl(null);verifications.save(v);
                continue;
            }
            if (v.getProvider()==KycProvider.VNPT && v.getExpiresAt()!=null && !v.getExpiresAt().isAfter(Instant.now())) {
                continue;
            }
            return new Session(v.getId(),v.getExpiresAt(),config.maxImageMb());
        }
        if(config.dailyAttempts()>0 && verifications.countByUserIdAndProviderAndCreatedAtAfter(userId,KycProvider.VNPT,Instant.now().minus(Duration.ofDays(1)))>=config.dailyAttempts())
            throw new AppException(UserErrorCode.KYC_ATTEMPT_LIMIT);
        String id=UUID.randomUUID().toString();
        KycVerification v=KycVerification.builder().id(id).userId(userId).provider(KycProvider.VNPT)
            .providerSessionId("WEB-SDK_HomeSpace_3.2.1_"+id+"_"+Instant.now().toEpochMilli()).status(KycStatus.PENDING).expiresAt(Instant.now().plus(Duration.ofMinutes(config.sessionMinutes()))).build();
        verifications.save(v);
        return new Session(id,v.getExpiresAt(),config.maxImageMb());
    }
    public record Session(String sessionId,Instant expiresAt,int maxImageMb) {}

    private KycVerification owned(String userId,String id,boolean allowVerified) {
        KycVerification v=verifications.lockById(id).filter(k -> userId.equals(k.getUserId()) && k.getProvider()==KycProvider.VNPT)
            .orElseThrow(() -> new AppException(UserErrorCode.KYC_SESSION_NOT_FOUND));
        if(allowVerified && v.getStatus()==KycStatus.VERIFIED)return v;
        if(v.getStatus()!=KycStatus.PENDING || v.getExpiresAt()==null || !v.getExpiresAt().isAfter(Instant.now()))
            throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        return v;
    }
    @Transactional
    public KycProviderStep prepare(String userId,String attempt,KycProviderStep call,List<String> references) {
        KycVerification verification = owned(userId,attempt,false);
        if("face".equals(call.getKind()) && references.size()!=3)throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        for(int i=0;i<references.size();i++) {
            String uploadKind="face".equals(call.getKind()) && i==2?"scan3d":"upload";
            if(!steps.existsByProviderAndAttemptIdAndUploadHashAndStateAndKind(KycProvider.VNPT,attempt,references.get(i),"DONE",uploadKind))
                throw new AppException(UserErrorCode.KYC_INPUT_INVALID);
        }
        var existing=steps.findById(call.getId());
        if(existing.isPresent()) {
            if(!"DONE".equals(existing.get().getState())) throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
            return existing.get();
        }
        if(steps.countByProviderAndAttemptId(KycProvider.VNPT,attempt)>=30)throw new AppException(UserErrorCode.KYC_ATTEMPT_LIMIT);
        call.setClientSession(verification.getProviderSessionId());
        call.setProvider(KycProvider.VNPT);call.setAttemptId(attempt);call.setState("RUNNING");call.setCreatedAt(Instant.now());
        return steps.saveAndFlush(call);
    }
    @Transactional
    public void finish(String userId,KycProviderStep call,String result,String uploadHash) {
        owned(userId,call.getAttemptId(),false);
        KycProviderStep row=steps.findById(call.getId()).orElseThrow();
        if(!"RUNNING".equals(row.getState()))throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        row.setResultJson(result);row.setUploadHash(uploadHash);row.setState("DONE");steps.save(row);
    }
    @Transactional
    public void fail(String userId,KycProviderStep call) {
        var attempt=owned(userId,call.getAttemptId(),false);
        steps.findById(call.getId()).ifPresent(row -> {
            row.setState("FAILED");steps.save(row);
            if(attempt.getRetryNoticeSentAt()==null) {
                notifications.enqueueKyc(attempt,com.hs.user.notification.AccountNotificationWriter.KycEvent.KYC_RETRY_REQUIRED);
                attempt.setRetryNoticeSentAt(Instant.now());verifications.save(attempt);
            }
        });
    }
    @Transactional
    public KycStatus finalizeAttempt(String userId,String attempt) {
        claims.lockVerification(userId);
        KycVerification v=owned(userId,attempt,true);
        if(v.getStatus()==KycStatus.VERIFIED)return v.getStatus();
        List<KycProviderStep> all=steps.findByProviderAndAttemptIdOrderByCreatedAtAsc(KycProvider.VNPT,attempt);
        if(all.stream().anyMatch(s -> !"DONE".equals(s.getState())))throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        KycProviderStep ocr=last(all,"ocr"),compare=last(all,"compare"),face=last(all,"face"),mask=last(all,"mask");
        if(ocr==null || compare==null || face==null || mask==null || !Objects.equals(ocr.getFrontHash(),compare.getFrontHash()))
            throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        KycProviderStep front=card(all,ocr.getFrontHash()),back=card(all,ocr.getBackHash());
        String faceHash=compare.getFaceHash();
        if(faceHash==null)throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        KycProviderStep upload=all.stream().filter(s -> "upload".equals(s.getKind()) && faceHash.equals(s.getUploadHash())).findFirst().orElse(null);
        String faceReferenceDigest=VnptKycProxy.digest(faceHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(front==null || back==null || upload==null || !faceHash.equals(mask.getFaceHash())
            || !(faceReferenceDigest.equals(face.getNearDigest()) || faceReferenceDigest.equals(face.getFarDigest()))
            || Objects.equals(ocr.getFrontHash(),ocr.getBackHash())
            || faceHash.equals(ocr.getFrontHash()) || faceHash.equals(ocr.getBackHash()))throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        Set<String> selected=Set.of(ocr.getFrontHash(),ocr.getBackHash(),faceHash);
        if(all.stream().filter(s -> "upload".equals(s.getKind())).anyMatch(s -> !selected.contains(s.getUploadHash())))
            throw new AppException(UserErrorCode.KYC_ATTEMPT_INVALID);
        boolean passed=List.of(ocr,compare,face,mask,front,back).stream().allMatch(s -> VnptKycPolicy.passed(s.getKind(),result(s)));
        KycVerifiedDetailsResponse verifiedDetails = passed ? details(ocr, compare, face, mask, front, back) : null;
        if(!passed) {
            v.setStatus(KycStatus.REJECTED);v.setRejectionReason("Giấy tờ hoặc khuôn mặt chưa đáp ứng yêu cầu xác minh. Vui lòng thử lại.");
        }else if(!claims.claimInCurrentTransaction(userId,result(ocr).path("object").path("id").asText())) {
            v.setStatus(KycStatus.REJECTED);v.setRejectionReason("Số định danh đã được xác minh trên tài khoản khác.");
        }else {
            v.setStatus(KycStatus.VERIFIED);v.setVerifiedAt(Instant.now());v.setRejectionReason(null);
            detailsRepository.save(UserKycDetails.from(userId, verifiedDetails));
        }
        verifications.save(v);
        notifications.enqueueKyc(v,v.getStatus()==KycStatus.VERIFIED
            ?com.hs.user.notification.AccountNotificationWriter.KycEvent.KYC_VERIFIED
            :com.hs.user.notification.AccountNotificationWriter.KycEvent.KYC_REJECTED);
        return v.getStatus();
    }
    private KycVerifiedDetailsResponse details(KycProviderStep ocr, KycProviderStep compare, KycProviderStep face,
                           KycProviderStep mask, KycProviderStep front, KycProviderStep back) {
        try {
            return VnptKycDetails.from(result(ocr),result(compare),result(face),
                    result(mask),result(front),result(back),mapper);
        } catch (Exception ex) { throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR); }
    }
    private JsonNode result(KycProviderStep s) {
        try{return mapper.readTree(s.getResultJson());}catch(Exception ex){throw new AppException(UserErrorCode.KYC_PROVIDER_ERROR);}
    }
    private static KycProviderStep last(List<KycProviderStep> all,String kind) {
        for(int i=all.size()-1;i>=0;i--)if(kind.equals(all.get(i).getKind()) && "DONE".equals(all.get(i).getState()))return all.get(i);
        return null;
    }
    private static KycProviderStep card(List<KycProviderStep> all,String hash) {
        return all.stream().filter(s -> "card".equals(s.getKind()) && "DONE".equals(s.getState()) && Objects.equals(hash,s.getFrontHash())).reduce((a,b)->b).orElse(null);
    }
    @Scheduled(fixedDelay=3600000) @Transactional
    public void purgeTemporaryEvidence() {
        steps.deleteByProviderAndCreatedAtBefore(KycProvider.VNPT,Instant.now().minus(Duration.ofDays(1)));
    }
}
