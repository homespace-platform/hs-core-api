package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.UserContext;
import com.hs.common.context.UserContextHolder;
import com.hs.common.security.SecretCrypto;
import com.hs.user.dto.request.AdminKycRequest;
import com.hs.user.dto.response.AdminKycStatusResponse;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.model.KycVerification;
import com.hs.user.model.User;
import com.hs.user.model.UserKycDetails;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserKycDetailsRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.security.KycCryptoConverter;
import com.hs.user.service.KycService;
import com.hs.user.service.impl.CccdClaimService;
import com.hs.user.service.impl.KycServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminKycServiceTest {
    KycService kyc = mock(KycService.class);
    UserRepository users = mock(UserRepository.class);
    KycVerificationRepository verifications = mock(KycVerificationRepository.class);
    UserKycDetailsRepository details = mock(UserKycDetailsRepository.class);
    CccdClaimService claims = mock(CccdClaimService.class);
    AccountNotificationWriter notices = mock(AccountNotificationWriter.class);
    AdminKycService service = new AdminKycService(kyc, users, verifications, details, claims, notices);

    @BeforeEach void setUp() {
        SecretCrypto.configure("test-secret");
        when(users.existsById("alice")).thenReturn(true);
        when(claims.claimInCurrentTransaction(eq("alice"), anyString())).thenReturn(true);
        when(verifications.findFirstByUserIdAndStatusOrderByVerifiedAtDesc(any(), any())).thenReturn(Optional.empty());
    }
    @AfterEach void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); }

    private static void login(String... authorities) {
        UserContextHolder.set(new UserContext("actor", null));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "actor", "n/a", Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    private static AdminKycRequest req() {
        return new AdminKycRequest("001234567890", " NGUYEN VAN A ", "2000-01-31", "Nam", " Ha Noi ", "2020-02-03", "2040-01-01", "Cuc CSQLHC");
    }

    private AdminKycRequest with(int field, String value) {
        var r = req();
        var v = new String[]{r.cccd(), r.fullName(), r.birthDate(), r.gender(), r.permanentAddress(), r.issueDate(), r.expirationDate(), r.issuePlace()};
        v[field] = value;
        return new AdminKycRequest(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7]);
    }

    @Test void adminSetsKycForUnverifiedUserWithFullPassAnd100Percent() {
        login("ADMIN", "KYC_MANAGE");
        var out = service.set("alice", req());
        assertTrue(out.verified());
        assertEquals("ADMIN", out.provider());
        assertEquals("Quản trị viên", out.providerLabel());
        assertNotNull(out.verifiedAt());
        var saved = ArgumentCaptor.forClass(UserKycDetails.class);
        verify(details).save(saved.capture());
        var d = saved.getValue();
        assertEquals("alice", d.getUserId());
        assertEquals("NGUYEN VAN A", d.getFullName());
        assertEquals("Ha Noi", d.getPermanentAddress());
        assertEquals("31/01/2000", d.getBirthDate());
        assertEquals("03/02/2020", d.getIssueDate());
        assertEquals("01/01/2040", d.getExpirationDate());
        assertEquals(100.0, d.getFaceMatchPercent());
        assertTrue(Arrays.asList(d.getDocumentValid(), d.getFrontBackIdentical(), d.getClearCorners(), d.getIdQualityGood(),
                d.getIssueDateQualityGood(), d.getUnexpired(), d.getLiveDocument(), d.getNoWarnings(), d.getNoOverlayImage(),
                d.getEyesOpen(), d.getFaceNotBlurred(), d.getFaceValid(), d.getFaceNotCovered()).stream().allMatch(Boolean.TRUE::equals));
        var v = ArgumentCaptor.forClass(KycVerification.class);
        verify(verifications).save(v.capture());
        assertEquals(KycProvider.ADMIN, v.getValue().getProvider());
        assertEquals(KycStatus.VERIFIED, v.getValue().getStatus());
        verify(notices).enqueueKyc(v.getValue(), AccountNotificationWriter.KycEvent.KYC_VERIFIED);
    }

    @Test void adminOverwritesAlreadyVerifiedUserKeepingTheSameRecord() {
        login("ADMIN", "KYC_MANAGE");
        var old = KycVerification.builder().id("v1").userId("alice").provider(KycProvider.VNPT).providerSessionId("s")
                .status(KycStatus.VERIFIED).verifiedAt(Instant.EPOCH).build();
        when(verifications.findFirstByUserIdAndStatusOrderByVerifiedAtDesc("alice", KycStatus.VERIFIED)).thenReturn(Optional.of(old));
        when(verifications.lockById("v1")).thenReturn(Optional.of(old));
        var out = service.set("alice", req());
        assertEquals("v1", old.getId());
        assertEquals(KycProvider.ADMIN, old.getProvider());
        assertTrue(old.getVerifiedAt().isAfter(Instant.EPOCH));
        assertEquals("Quản trị viên", out.providerLabel());
        verify(details).save(any(UserKycDetails.class));
    }

    @Test void nonAdminIsRejectedBeforeAnyWrite() {
        login("USER", "KYC_MANAGE");
        assertThrows(AppException.class, () -> service.set("alice", req()));
        verifyNoInteractions(details, verifications, claims, notices);
    }

    @Test void cccdOwnedByAnotherUserIsConflictAndNothingIsWritten() {
        login("ADMIN", "KYC_MANAGE");
        when(claims.claimInCurrentTransaction("alice", "001234567890")).thenReturn(false);
        var e = assertThrows(AppException.class, () -> service.set("alice", req()));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(2512, e.getCode());
        verify(details, never()).save(any());
        verify(verifications, never()).save(any());
    }

    @Test void unknownUserIs404() {
        login("ADMIN", "KYC_MANAGE");
        when(users.existsById("ghost")).thenReturn(false);
        assertEquals(404, assertThrows(AppException.class, () -> service.set("ghost", req())).getStatusCode().value());
    }

    @Test void invalidInputIs400WithVietnameseMessageAndNoWrite() {
        login("ADMIN", "KYC_MANAGE");
        for (var bad : new AdminKycRequest[]{
                with(0, "12345"), with(1, " "), with(2, "2000-02-31"), with(2, "31/01/2000"), with(3, "Khac"),
                with(4, ""), with(7, null), with(5, "2999-01-01"), with(6, "2020-02-03"), with(2, "2020-02-03")}) {
            var e = assertThrows(AppException.class, () -> service.set("alice", bad));
            assertEquals(400, e.getStatusCode().value());
            assertTrue(e.getErrorMessage().contains(" "), e.getErrorMessage());
        }
        verify(details, never()).save(any());
        verify(verifications, never()).save(any());
    }

    @Test void storedValuesAreEncryptedByTheEntityConverterAndUserSeesThemAndGatePasses() throws Exception {
        login("ADMIN", "KYC_MANAGE");
        var saved = ArgumentCaptor.forClass(UserKycDetails.class);
        var ver = ArgumentCaptor.forClass(KycVerification.class);
        service.set("alice", req());
        verify(details).save(saved.capture());
        verify(verifications).save(ver.capture());
        var d = saved.getValue();
        var conv = new KycCryptoConverter();
        for (String f : new String[]{"cccd", "fullName", "birthDate", "permanentAddress", "gender", "issueDate", "expirationDate", "issuePlace"}) {
            var field = UserKycDetails.class.getDeclaredField(f);
            assertTrue(field.isAnnotationPresent(jakarta.persistence.Convert.class), f);
            field.setAccessible(true);
            String plain = (String) field.get(d);
            String stored = conv.convertToDatabaseColumn(plain);
            assertTrue(stored.startsWith("v1:") && !stored.contains(plain), f);
            assertEquals(plain, conv.convertToEntityAttribute(stored));
        }
        var hook = UserKycDetails.class.getDeclaredMethod("syncCccdHash");
        hook.setAccessible(true);
        hook.invoke(d);
        assertEquals(SecretCrypto.get().cccdHash("001234567890"), d.getCccdHash());

        var userRepo = mock(UserRepository.class);
        when(userRepo.existsById("alice")).thenReturn(true);
        var vr = mock(KycVerificationRepository.class);
        when(vr.findFirstByUserIdAndStatusOrderByVerifiedAtDesc("alice", KycStatus.VERIFIED)).thenReturn(Optional.of(ver.getValue()));
        var dr = mock(UserKycDetailsRepository.class);
        when(dr.findById("alice")).thenReturn(Optional.of(d));
        var shown = new KycServiceImpl(userRepo, vr, dr).getVerifiedDetails("alice");
        assertEquals("001234567890", shown.cccd());
        assertEquals("NGUYEN VAN A", shown.fullName());
        assertEquals("31/01/2000", shown.birthDate());
        assertEquals(100.0, shown.faceMatchPercent());
        assertTrue(shown.faceValid());

        var gateRepo = mock(KycVerificationRepository.class);
        when(gateRepo.existsByUserIdAndProviderInAndStatus(eq("alice"), any(), eq(KycStatus.VERIFIED)))
                .thenAnswer(i -> ((java.util.Collection<?>) i.getArgument(1)).contains(ver.getValue().getProvider()));
        var user = new User();
        user.setId("alice");
        var userRepo2 = mock(UserRepository.class);
        when(userRepo2.findById("alice")).thenReturn(Optional.of(user));
        assertDoesNotThrow(() -> new KycGateService(userRepo2, gateRepo).requireVerifiedIdentity("alice"));
    }

    @Test void statusExposesOnlyStateProviderAndTimeNeverPersonalData() {
        login("ADMIN", "KYC_VIEW");
        var at = Instant.parse("2026-11-27T00:00:00Z");
        when(kyc.getCurrentStatus("alice")).thenReturn(new KycStatusResponse(KycStatus.VERIFIED, at, KycProvider.ADMIN, "s", null, null));
        assertEquals(new AdminKycStatusResponse(true, "ADMIN", "Quản trị viên", at), service.status("alice"));
        when(kyc.getCurrentStatus("bob")).thenReturn(new KycStatusResponse(KycStatus.NOT_VERIFIED, null, KycProvider.VNPT, null, null, null));
        assertFalse(service.status("bob").verified());
        verifyNoInteractions(details);
    }

    @Test void adminWithoutKycViewCannotReadStatus() {
        login("ADMIN");
        assertThrows(AppException.class, () -> service.status("alice"));
        verifyNoInteractions(kyc);
    }
}
