package com.hs.payment.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.payment.model.constant.BankAccountStatus;
import com.hs.payment.model.constant.BankAccountVerificationMethod;
import com.hs.payment.security.BankAccountCryptoConverter;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Entity
@Table(
        name = "bank_accounts",
        indexes = {
                @Index(name = "idx_bank_account_user", columnList = "user_id"),
                @Index(name = "idx_bank_account_user_status", columnList = "user_id, status")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class BankAccount extends BaseEntity {

    @Id
    @Column(length = 36, updatable = false)
    String id;

    @Column(name = "user_id", nullable = false, length = 36)
    String userId;

    @Column(name = "bank_bin", nullable = false, length = 20)
    String bankBin;

    @Column(name = "bank_code", nullable = false, length = 30)
    String bankCode;

    @Column(name = "bank_name", nullable = false, length = 100)
    String bankName;

    @Convert(converter = BankAccountCryptoConverter.class)
    @Column(name = "account_number", nullable = false, length = 255)
    String accountNumber;

    @Column(name = "account_holder_name", nullable = false, length = 100)
    String accountHolderName;

    @Builder.Default
    @Column(name = "default_for_incoming_payments", nullable = false)
    Boolean defaultForIncomingPayments = false;

    @Builder.Default
    @Column(name = "default_for_refunds", nullable = false)
    Boolean defaultForRefunds = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    BankAccountStatus status = BankAccountStatus.ACTIVE;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_method", nullable = false, length = 30)
    BankAccountVerificationMethod verificationMethod = BankAccountVerificationMethod.USER_DECLARED;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (status == null) {
            status = BankAccountStatus.ACTIVE;
        }
        if (verificationMethod == null) {
            verificationMethod = BankAccountVerificationMethod.USER_DECLARED;
        }
        if (defaultForIncomingPayments == null) {
            defaultForIncomingPayments = false;
        }
        if (defaultForRefunds == null) {
            defaultForRefunds = false;
        }
    }

    public String getMaskedAccountNumber() {
        if (accountNumber == null || accountNumber.isBlank()) {
            return "";
        }
        String clean = accountNumber.trim();
        if (clean.length() <= 4) {
            return "****";
        }
        return "******" + clean.substring(clean.length() - 4);
    }
}
