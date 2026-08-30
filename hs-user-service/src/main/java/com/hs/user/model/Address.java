package com.hs.user.model;

import java.util.UUID;

import com.hs.common.persistence.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "addresses")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@FieldDefaults(level = AccessLevel.PRIVATE)
public class Address extends BaseEntity {

    @Id
    @Column(nullable = false, unique = true)
    String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    User user;

    @Column(name = "listing_id", unique = true, length = 36)
    String listingId;

    @Column(name = "province_code", nullable = false, length = 20)
    String provinceCode;

    @Column(name = "province_name", nullable = false, length = 100)
    String provinceName;

    @Column(name = "ward_code", nullable = false, length = 20)
    String wardCode;

    @Column(name = "ward_name", nullable = false, length = 100)
    String wardName;

    @Column(name = "street_line", nullable = false, length = 255)
    String streetLine;

    @Column(name = "full_address", nullable = false, length = 500)
    String fullAddress;

    @PrePersist
    void prePersist() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }
}
