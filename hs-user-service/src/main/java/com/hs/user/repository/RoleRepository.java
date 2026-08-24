package com.hs.user.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.user.model.Role;
import java.util.List;
import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, String> {

    Optional<Role> findByName(String name);

    Optional<Role> findByIdAndActiveTrue(String id);

    Page<Role> findAllByActiveTrue(Pageable pageable);

    List<Role> findAllByActiveTrue();

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, String id);
}

