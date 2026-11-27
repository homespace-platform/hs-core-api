package com.hs.user.repository;

import com.hs.user.model.UserKycDetails;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserKycDetailsRepository extends JpaRepository<UserKycDetails, String> {
    boolean existsByCccdHashAndUserIdNot(String cccdHash, String userId);
}
