package com.hs.user.model;

import java.time.LocalDate;

import com.hs.common.persistence.BaseEntity;
import com.hs.user.model.constant.Gender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

@Entity
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

    LocalDate dob;

    @Enumerated(EnumType.STRING)
    Gender gender;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "role_id")
    Role role;

    @Column(name = "on_boarded", nullable = false)
    Boolean onBoarded = false;
}

