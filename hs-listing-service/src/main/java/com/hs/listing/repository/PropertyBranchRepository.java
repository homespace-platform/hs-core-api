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
}
