package com.hs.storage.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Entity
@Table(name = "storage_objects", indexes = {
        @Index(name = "idx_storage_owner", columnList = "owner_id"),
        @Index(name = "idx_storage_reference", columnList = "reference_type, reference_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class StorageObject extends BaseEntity {
    @Id
    @Column(nullable = false, updatable = false, length = 36)
    String id;

    @Column(name = "original_name", nullable = false, length = 512)
    String originalName;

    @Column(name = "object_key", nullable = false, unique = true, length = 1024)
    String objectKey;

    @Column(name = "bucket_name", nullable = false)
    String bucketName;

    @Column(name = "content_type", nullable = false)
    String contentType;

    @Column(name = "size_bytes", nullable = false)
    Long sizeBytes;

    @Column(length = 128)
    String checksum;

    @Column(length = 32)
    String extension;

    @Column(name = "owner_id", nullable = false)
    String ownerId;

    @Column(name = "reference_type", length = 50)
    String referenceType;

    @Column(name = "reference_id")
    String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    StoragePurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    StorageVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    StorageStatus status;
}
