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

    @org.springframework.data.jpa.repository.Query("select b.id from PropertyBranch b "
            + "where b.ownerId = :ownerId and b.active = true")
    List<String> findActiveIds(@org.springframework.data.repository.query.Param("ownerId") String ownerId);

    /** Owners that have an active branch, in id order after {@code after} (keyset page). */
    @org.springframework.data.jpa.repository.Query("select distinct b.ownerId from PropertyBranch b "
            + "where b.active = true and b.ownerId > :after order by b.ownerId")
    List<String> findActiveOwnerIds(@org.springframework.data.repository.query.Param("after") String after,
            Pageable pageable);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT b FROM PropertyBranch b WHERE b.id = :id AND b.active = true")
    Optional<PropertyBranch> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);

    /*
     * Lock order. findByIdForUpdate is FOR NO KEY UPDATE and is what parking and acceptance use; they take it
     * before or after the listing row. Paths that serialise with a listing changing branch take FOR UPDATE and
     * never lock the listing row. Listing writes take only FOR KEY SHARE, which conflicts with FOR UPDATE and
     * nothing else, so they can never wait for parking while holding their listing.
     */
    @org.springframework.data.jpa.repository.Query(value = "SELECT id FROM property_branches WHERE id = :id FOR UPDATE", nativeQuery = true)
    List<String> lockExclusive(@org.springframework.data.repository.query.Param("id") String id);

    /** Returns the committed active flag as of after any concurrent delete; no active filter, so false means deleted. */
    @org.springframework.data.jpa.repository.Query(value = "SELECT active FROM property_branches WHERE id = :id FOR KEY SHARE", nativeQuery = true)
    Optional<Boolean> lockForListingWrite(@org.springframework.data.repository.query.Param("id") String id);
}
