package com.hs.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.user.model.Permission;

public interface PermissionRepository extends JpaRepository<Permission, String> {
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);
}

