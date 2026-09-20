package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.dto.BankAccountRequest;
import com.hs.payment.dto.BankAccountResponse;
import com.hs.payment.dto.UpdateBankAccountDefaultsRequest;
import com.hs.payment.model.BankAccount;
import com.hs.payment.model.constant.BankAccountStatus;
import com.hs.payment.repository.BankAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BankAccountServiceTest {

    @Mock
    private BankAccountRepository bankAccountRepository;

    @InjectMocks
    private BankAccountService bankAccountService;

    private BankAccountRequest sampleRequest;

    @BeforeEach
    void setUp() {
        sampleRequest = new BankAccountRequest(
                "970422",
                "MB",
                "MB Bank",
                "0987654321",
                "NGUYEN VAN A",
                false,
                false
        );
    }

    @Test
    @DisplayName("Create first bank account - automatically sets incoming and refund defaults")
    void createFirstBankAccount_SetsDefaults() {
        when(bankAccountRepository.existsByUserIdAndActiveTrue("user-1")).thenReturn(false);
        when(bankAccountRepository.save(any(BankAccount.class))).thenAnswer(invocation -> {
            BankAccount acc = invocation.getArgument(0);
            acc.setId("acc-1");
            return acc;
        });

        BankAccountResponse resp = bankAccountService.createBankAccount("user-1", sampleRequest);

        assertNotNull(resp);
        assertEquals("acc-1", resp.id());
        assertTrue(resp.defaultForIncomingPayments());
        assertTrue(resp.defaultForRefunds());
        assertEquals("******4321", resp.accountNumber()); // Masked
        assertEquals("NGUYEN VAN A", resp.accountHolderName());
    }

    @Test
    @DisplayName("Create secondary bank account with explicit defaults resets old defaults")
    void createSecondBankAccount_ExplicitDefaults_UnsetsOld() {
        when(bankAccountRepository.existsByUserIdAndActiveTrue("user-1")).thenReturn(true);
        BankAccount oldDefault = BankAccount.builder()
                .id("acc-old")
                .userId("user-1")
                .defaultForIncomingPayments(true)
                .defaultForRefunds(true)
                .build();
        when(bankAccountRepository.findByUserIdAndDefaultForIncomingPaymentsTrueAndStatusAndActiveTrue(
                "user-1", BankAccountStatus.ACTIVE)).thenReturn(Optional.of(oldDefault));
        when(bankAccountRepository.findByUserIdAndDefaultForRefundsTrueAndStatusAndActiveTrue(
                "user-1", BankAccountStatus.ACTIVE)).thenReturn(Optional.of(oldDefault));

        BankAccountRequest reqWithDefaults = new BankAccountRequest(
                "970436", "VCB", "Vietcombank", "1234567890", "NGUYEN VAN A", true, true
        );

        when(bankAccountRepository.save(any(BankAccount.class))).thenAnswer(i -> i.getArgument(0));

        BankAccountResponse resp = bankAccountService.createBankAccount("user-1", reqWithDefaults);

        assertTrue(resp.defaultForIncomingPayments());
        assertTrue(resp.defaultForRefunds());
        assertFalse(oldDefault.getDefaultForIncomingPayments());
        assertFalse(oldDefault.getDefaultForRefunds());
    }

    @Test
    @DisplayName("Create bank account without required fields throws exception")
    void createBankAccount_MissingFields_ThrowsException() {
        BankAccountRequest invalidReq = new BankAccountRequest(
                "", "", "", "", "", false, false
        );

        AppException ex = assertThrows(AppException.class, () ->
                bankAccountService.createBankAccount("user-1", invalidReq));
        assertEquals(PaymentErrorCode.BANK_ACCOUNT_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("Delete non-default bank account deactivates it")
    void deleteBankAccount_NonDefault_Success() {
        BankAccount acc = BankAccount.builder()
                .id("acc-2")
                .userId("user-1")
                .defaultForIncomingPayments(false)
                .defaultForRefunds(false)
                .status(BankAccountStatus.ACTIVE)
                .build();
        acc.setActive(true);

        when(bankAccountRepository.findByIdAndUserIdAndActiveTrue("acc-2", "user-1"))
                .thenReturn(Optional.of(acc));

        bankAccountService.deleteBankAccount("acc-2", "user-1");

        assertFalse(acc.getActive());
        assertEquals(BankAccountStatus.INACTIVE, acc.getStatus());
        verify(bankAccountRepository).save(acc);
    }

    @Test
    @DisplayName("Delete default bank account when user has multiple accounts throws exception")
    void deleteDefaultBankAccount_MultipleAccounts_ThrowsException() {
        BankAccount defaultAcc = BankAccount.builder()
                .id("acc-1")
                .userId("user-1")
                .defaultForIncomingPayments(true)
                .defaultForRefunds(true)
                .status(BankAccountStatus.ACTIVE)
                .build();
        defaultAcc.setActive(true);

        when(bankAccountRepository.findByIdAndUserIdAndActiveTrue("acc-1", "user-1"))
                .thenReturn(Optional.of(defaultAcc));
        when(bankAccountRepository.findByUserIdAndActiveTrue("user-1"))
                .thenReturn(List.of(defaultAcc, new BankAccount()));

        AppException ex = assertThrows(AppException.class, () ->
                bankAccountService.deleteBankAccount("acc-1", "user-1"));

        assertEquals(PaymentErrorCode.BANK_ACCOUNT_DEFAULT_DELETE_FORBIDDEN.getCode(), ex.getCode());
    }
}
