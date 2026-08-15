package com.hs.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.user.model.Role;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByName(String name);

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);
}

