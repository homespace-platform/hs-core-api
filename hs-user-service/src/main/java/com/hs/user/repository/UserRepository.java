package com.hs.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hs.user.model.User;


public interface UserRepository extends JpaRepository<User, String> {
    @org.springframework.data.jpa.repository.Query(value = "SELECT * FROM users WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<User> lockMembershipOwner(@org.springframework.data.repository.query.Param("id") String id);
    /** Serializes admin role/status changes on one user row; the row is read after the lock is granted. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") String id);
    Optional<User> findByEmail(String email);

    boolean existsByRole_Name(String roleName);

    boolean existsByUsernameAndIdNot(String username, String id);

    boolean existsByEmailAndIdNot(String email, String id);

    boolean existsByPhoneAndIdNot(String phone, String id);

    boolean existsByCccdAndIdNot(String cccd, String id);
}
