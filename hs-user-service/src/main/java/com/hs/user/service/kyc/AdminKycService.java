package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.CurrentUser;
import com.hs.common.security.Permissions;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.dto.request.AdminKycRequest;
import com.hs.user.dto.response.AdminKycStatusResponse;
import com.hs.user.dto.response.KycStatusResponse;
import com.hs.user.dto.response.KycVerifiedDetailsResponse;
import com.hs.user.model.KycVerification;
import com.hs.user.model.UserKycDetails;
import com.hs.user.model.constant.KycProvider;
import com.hs.user.model.constant.KycStatus;
import com.hs.user.notification.AccountNotificationWriter;
import com.hs.user.repository.KycVerificationRepository;
import com.hs.user.repository.UserKycDetailsRepository;
import com.hs.user.repository.UserRepository;
import com.hs.user.service.KycService;
import com.hs.user.service.impl.CccdClaimService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service @RequiredArgsConstructor
public class AdminKycService {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter STORED = DateTimeFormatter.ofPattern("dd/MM/yyyy"); // same style as VNPT data
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    private final KycService kyc;
    private final UserRepository users;
    private final KycVerificationRepository verifications;
    private final UserKycDetailsRepository detailsRepository;
    private final CccdClaimService claims;
    private final AccountNotificationWriter notifications;

    @Transactional(readOnly = true)
    public AdminKycStatusResponse status(String userId) {
        CurrentUser.requireAdminWith(Permissions.KYC_VIEW);
        return view(kyc.getCurrentStatus(userId));
    }

    /** Writes the same verified state a successful VNPT run leaves, with provider ADMIN; overwrites any previous values. */
    @Transactional
    public AdminKycStatusResponse set(String userId, AdminKycRequest r) {
        CurrentUser.requireAdminWith(Permissions.KYC_MANAGE);
        String adminId = CurrentUser.requireUserId();
        if (r == null) throw bad("Thiếu dữ liệu xác thực KYC.");
        if (!users.existsById(userId)) throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        var value = validate(r);
        claims.lockVerification(userId);
        if (!claims.claimInCurrentTransaction(userId, value.cccd())) throw new AppException(UserErrorCode.KYC_CCCD_ALREADY_USED);
        detailsRepository.save(UserKycDetails.from(userId, value));

        KycVerification v = verifications.findFirstByUserIdAndStatusOrderByVerifiedAtDesc(userId, KycStatus.VERIFIED)
                .flatMap(old -> verifications.lockById(old.getId()))
                .orElseGet(() -> KycVerification.builder().id(UUID.randomUUID().toString()).userId(userId)
                        .providerSessionId("ADMIN_" + UUID.randomUUID()).build());
        v.setProvider(KycProvider.ADMIN);
        v.setStatus(KycStatus.VERIFIED);
        v.setVerifiedAt(Instant.now());
        v.setRejectionReason(null);
        v.setSessionUrl(null);
        verifications.save(v);
        notifications.enqueueKyc(v, AccountNotificationWriter.KycEvent.KYC_VERIFIED);
        log.info("Admin KYC set adminId={} userId={} at={}", adminId, userId, v.getVerifiedAt());
        return new AdminKycStatusResponse(true, v.getProvider().name(), v.getProvider().label(), v.getVerifiedAt());
    }

    private static AdminKycStatusResponse view(KycStatusResponse s) {
        boolean ok = s.status() == KycStatus.VERIFIED;
        return new AdminKycStatusResponse(ok, ok ? s.provider().name() : null, ok ? s.provider().label() : null, ok ? s.verifiedAt() : null);
    }

    private static KycVerifiedDetailsResponse validate(AdminKycRequest r) {
        String cccd = text(r.cccd(), "CCCD");
        if (!cccd.matches("\\d{12}")) throw bad("CCCD phải gồm đúng 12 chữ số.");
        String name = text(r.fullName(), "Họ và tên"), address = text(r.permanentAddress(), "Nơi thường trú"),
                place = text(r.issuePlace(), "Nơi cấp"), gender = text(r.gender(), "Giới tính");
        if (!gender.equals("Nam") && !gender.equals("Nữ")) throw bad("Giới tính phải là Nam hoặc Nữ.");
        LocalDate birth = date(r.birthDate(), "Ngày sinh"), issue = date(r.issueDate(), "Ngày cấp"),
                expiry = date(r.expirationDate(), "Ngày hết hạn");
        if (issue.isAfter(LocalDate.now(VN))) throw bad("Ngày cấp không được sau ngày hôm nay.");
        if (!issue.isBefore(expiry)) throw bad("Ngày cấp phải trước ngày hết hạn.");
        if (!birth.isBefore(issue)) throw bad("Ngày sinh phải trước ngày cấp.");
        // Owner rule: server sets every score to 100 and every check to pass; the request cannot carry them.
        return new KycVerifiedDetailsResponse(cccd, name, birth.format(STORED), address, gender, issue.format(STORED),
                expiry.format(STORED), place, 100.0, true, true, true, true, true, true, true, true, true, true, true, true, true);
    }

    private static String text(String v, String label) {
        if (v == null || v.isBlank()) throw bad(label + " là bắt buộc.");
        return v.trim();
    }

    private static LocalDate date(String v, String label) {
        try { return LocalDate.parse(text(v, label), DATE); }
        catch (DateTimeParseException e) { throw bad(label + " không hợp lệ (định dạng yyyy-MM-dd)."); }
    }

    private static AppException bad(String message) { return new AppException(UserErrorCode.KYC_INPUT_INVALID, message); }
}
