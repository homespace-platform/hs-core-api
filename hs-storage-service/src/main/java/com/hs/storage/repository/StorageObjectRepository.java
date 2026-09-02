package com.hs.storage.repository;

import com.hs.storage.model.StorageObject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StorageObjectRepository
        extends JpaRepository<StorageObject, String>, JpaSpecificationExecutor<StorageObject> {
}
