package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.BankAccountRequest;
import com.hs.payment.dto.BankAccountResponse;
import com.hs.payment.dto.UpdateBankAccountDefaultsRequest;
import com.hs.payment.model.BankAccount;
import com.hs.payment.model.constant.BankAccountStatus;
import com.hs.payment.model.constant.BankAccountVerificationMethod;
import com.hs.payment.repository.BankAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;

    @Transactional
    public BankAccountResponse createBankAccount(String userId, BankAccountRequest request) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(PaymentErrorCode.BANK_ACCOUNT_FORBIDDEN);
        }

        validateRequest(request);

        boolean hasExisting = bankAccountRepository.existsByUserIdAndActiveTrue(userId);

        boolean defaultIncoming = Boolean.TRUE.equals(request.defaultForIncomingPayments()) || !hasExisting;
        boolean defaultRefund = Boolean.TRUE.equals(request.defaultForRefunds()) || !hasExisting;

        if (defaultIncoming) {
            unsetExistingDefaultIncoming(userId);
        }
        if (defaultRefund) {
            unsetExistingDefaultRefund(userId);
        }

        BankAccount account = BankAccount.builder()
                .userId(userId)
                .bankBin(request.bankBin().trim())
                .bankCode(request.bankCode().trim().toUpperCase())
                .bankName(request.bankName().trim())
                .accountNumber(request.accountNumber().trim())
                .accountHolderName(request.accountHolderName().trim().toUpperCase())
                .defaultForIncomingPayments(defaultIncoming)
                .defaultForRefunds(defaultRefund)
                .status(BankAccountStatus.ACTIVE)
                .verificationMethod(BankAccountVerificationMethod.USER_DECLARED)
                .build();

        BankAccount saved = bankAccountRepository.save(account);
        log.info("Created bank account for user [{}], bankCode=[{}], masked=[{}]",
                userId, saved.getBankCode(), saved.getMaskedAccountNumber());

        return toResponse(saved, true);
    }

    @Transactional(readOnly = true)
    public List<BankAccountResponse> getUserBankAccounts(String userId) {
        List<BankAccount> accounts = bankAccountRepository.findByUserIdAndActiveTrue(userId);
        return accounts.stream()
                .map(acc -> toResponse(acc, true))
                .toList();
    }

    @Transactional(readOnly = true)
    public BankAccountResponse getBankAccount(String id, String userId, boolean mask) {
        BankAccount account = bankAccountRepository.findByIdAndUserIdAndActiveTrue(id, userId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.BANK_ACCOUNT_NOT_FOUND));
        return toResponse(account, mask);
    }

    @Transactional
    public BankAccountResponse updateBankAccount(String id, String userId, BankAccountRequest request) {
        BankAccount account = bankAccountRepository.findByIdAndUserIdAndActiveTrue(id, userId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.BANK_ACCOUNT_NOT_FOUND));

        validateRequest(request);

        if (Boolean.TRUE.equals(request.defaultForIncomingPayments()) && !Boolean.TRUE.equals(account.getDefaultForIncomingPayments())) {
            unsetExistingDefaultIncoming(userId);
            account.setDefaultForIncomingPayments(true);
        }
        if (Boolean.TRUE.equals(request.defaultForRefunds()) && !Boolean.TRUE.equals(account.getDefaultForRefunds())) {
            unsetExistingDefaultRefund(userId);
            account.setDefaultForRefunds(true);
        }

        account.setBankBin(request.bankBin().trim());
        account.setBankCode(request.bankCode().trim().toUpperCase());
        account.setBankName(request.bankName().trim());
        account.setAccountNumber(request.accountNumber().trim());
        account.setAccountHolderName(request.accountHolderName().trim().toUpperCase());

        BankAccount saved = bankAccountRepository.save(account);
        log.info("Updated bank account [{}] for user [{}]", id, userId);
        return toResponse(saved, true);
    }

    @Transactional
    public BankAccountResponse updateDefaults(String id, String userId, UpdateBankAccountDefaultsRequest request) {
        BankAccount account = bankAccountRepository.findByIdAndUserIdAndActiveTrue(id, userId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.BANK_ACCOUNT_NOT_FOUND));

        if (Boolean.TRUE.equals(request.defaultForIncomingPayments())) {
            unsetExistingDefaultIncoming(userId);
            account.setDefaultForIncomingPayments(true);
        }
        if (Boolean.TRUE.equals(request.defaultForRefunds())) {
            unsetExistingDefaultRefund(userId);
            account.setDefaultForRefunds(true);
        }

        BankAccount saved = bankAccountRepository.save(account);
        return toResponse(saved, true);
    }

    @Transactional
    public void deleteBankAccount(String id, String userId) {
        BankAccount account = bankAccountRepository.findByIdAndUserIdAndActiveTrue(id, userId)
                .orElseThrow(() -> new AppException(PaymentErrorCode.BANK_ACCOUNT_NOT_FOUND));

        if (Boolean.TRUE.equals(account.getDefaultForIncomingPayments()) || Boolean.TRUE.equals(account.getDefaultForRefunds())) {
            long totalActive = bankAccountRepository.findByUserIdAndActiveTrue(userId).size();
            if (totalActive > 1) {
                throw new AppException(PaymentErrorCode.BANK_ACCOUNT_DEFAULT_DELETE_FORBIDDEN);
            }
        }

        account.setActive(false);
        account.setStatus(BankAccountStatus.INACTIVE);
        bankAccountRepository.save(account);
        log.info("Deactivated bank account [{}] for user [{}]", id, userId);
    }

    @Transactional(readOnly = true)
    public BankAccount getDefaultIncomingAccount(String userId) {
        return bankAccountRepository.findByUserIdAndDefaultForIncomingPaymentsTrueAndStatusAndActiveTrue(
                        userId, BankAccountStatus.ACTIVE)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYEE_BANK_ACCOUNT_REQUIRED));
    }

    @Transactional(readOnly = true)
    public BankAccount getDefaultRefundAccount(String userId) {
        return bankAccountRepository.findByUserIdAndDefaultForRefundsTrueAndStatusAndActiveTrue(
                        userId, BankAccountStatus.ACTIVE)
                .orElseThrow(() -> new AppException(PaymentErrorCode.PAYER_BANK_ACCOUNT_REQUIRED));
    }

    @Transactional(readOnly = true)
    public boolean hasActiveBankAccount(String userId) {
        return bankAccountRepository.existsByUserIdAndStatusAndActiveTrue(userId, BankAccountStatus.ACTIVE);
    }

    private void unsetExistingDefaultIncoming(String userId) {
        bankAccountRepository.findByUserIdAndDefaultForIncomingPaymentsTrueAndStatusAndActiveTrue(
                userId, BankAccountStatus.ACTIVE).ifPresent(old -> {
            old.setDefaultForIncomingPayments(false);
            bankAccountRepository.save(old);
        });
    }

    private void unsetExistingDefaultRefund(String userId) {
        bankAccountRepository.findByUserIdAndDefaultForRefundsTrueAndStatusAndActiveTrue(
                userId, BankAccountStatus.ACTIVE).ifPresent(old -> {
            old.setDefaultForRefunds(false);
            bankAccountRepository.save(old);
        });
    }

    private void validateRequest(BankAccountRequest req) {
        if (req.bankBin() == null || req.bankBin().isBlank()
                || req.bankCode() == null || req.bankCode().isBlank()
                || req.accountNumber() == null || req.accountNumber().isBlank()
                || req.accountHolderName() == null || req.accountHolderName().isBlank()) {
            throw new AppException(PaymentErrorCode.BANK_ACCOUNT_INVALID);
        }
    }

    public BankAccountResponse toResponse(BankAccount account, boolean mask) {
        return BankAccountResponse.builder()
                .id(account.getId())
                .userId(account.getUserId())
                .bankBin(account.getBankBin())
                .bankCode(account.getBankCode())
                .bankName(account.getBankName())
                .accountNumber(mask ? account.getMaskedAccountNumber() : account.getAccountNumber())
                .accountHolderName(account.getAccountHolderName())
                .defaultForIncomingPayments(account.getDefaultForIncomingPayments())
                .defaultForRefunds(account.getDefaultForRefunds())
                .status(account.getStatus())
                .verificationMethod(account.getVerificationMethod())
                .createdAt(account.getCreatedAt())
                .updatedAt(account.getUpdatedAt())
                .build();
    }
}
