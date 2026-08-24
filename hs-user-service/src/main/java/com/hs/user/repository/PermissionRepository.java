package com.hs.user.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.user.model.Permission;

public interface PermissionRepository extends JpaRepository<Permission, String> {
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);

    Optional<Permission> findByIdAndActiveTrue(String id);

    Page<Permission> findAllByActiveTrue(Pageable pageable);

    List<Permission> findAllByActiveTrue();
}
