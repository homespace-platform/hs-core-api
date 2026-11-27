package com.hs.user.model;

import java.time.Instant;

import com.hs.common.persistence.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;
import org.hibernate.annotations.DynamicUpdate;

@Entity
@DynamicUpdate
@Table(name = "users")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class User extends BaseEntity {

    @Id
    @Column(nullable = false, unique = true)
    String id;

    @Column(nullable = false, unique = true)
    String username;

    @Column(nullable = false, unique = true)
    String email;

    @Column(length = 50)
    String firstName;

    @Column(length = 50)
    String lastName;

    @Column(columnDefinition = "TEXT")
    String avatarUrl;

    @Column(name = "avatar_storage_id", length = 255)
    String avatarStorageId;

    @Column(length = 15, unique = true)
    String phone;

    /** Read-only projection; the citizen ID is stored only in user_kyc_details. */
    @org.hibernate.annotations.Formula("(select k.cccd from user_kyc_details k where k.user_id = id)")
    @Setter(AccessLevel.NONE)
    @Getter(AccessLevel.NONE)
    String cccd;

    /** The formula reads the stored column, so decrypt here (plaintext legacy passes through). */
    public String getCccd() {
        return com.hs.common.security.SecretCrypto.isKycCiphertext(cccd)
                ? com.hs.common.security.SecretCrypto.get().decryptKyc(cccd) : cccd;
    }

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    Role role;

    @Column(name = "on_boarded", nullable = false)
    Boolean onBoarded = false;

    @Column(name = "onboarding_version")
    Integer onboardingVersion;

    @Column(name = "onboarding_completed_at")
    Instant onboardingCompletedAt;
}

