package com.hs.listing.repository;

import com.hs.listing.model.PropertyBranch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface PropertyBranchRepository extends JpaRepository<PropertyBranch, String>, JpaSpecificationExecutor<PropertyBranch> {
    List<PropertyBranch> findAllByOwnerIdAndActiveTrue(String ownerId);
    Page<PropertyBranch> findAllByOwnerIdAndActiveTrue(String ownerId, Pageable pageable);
    Optional<PropertyBranch> findByIdAndOwnerIdAndActiveTrue(String id, String ownerId);
    Optional<PropertyBranch> findByIdAndActiveTrue(String id);
    boolean existsByCode(String code);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT b FROM PropertyBranch b WHERE b.id = :id AND b.active = true")
    Optional<PropertyBranch> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
}
