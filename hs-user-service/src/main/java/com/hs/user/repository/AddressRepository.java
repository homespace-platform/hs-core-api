package com.hs.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.user.model.Address;

public interface AddressRepository extends JpaRepository<Address, String> {

    Optional<Address> findByUser_Id(String userId);

    Optional<Address> findByUser_IdAndActiveTrue(String userId);

    boolean existsByUser_Id(String userId);
}
