package com.hs.storage.repository;

import com.hs.storage.model.StorageObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StorageObjectRepository extends JpaRepository<StorageObject, String> {
    Page<StorageObject> findAllByOwnerIdAndActiveTrue(String ownerId, Pageable pageable);

    Page<StorageObject> findAllByOwnerIdAndReferenceTypeAndReferenceIdAndActiveTrue(
            String ownerId, String referenceType, String referenceId, Pageable pageable);
}
