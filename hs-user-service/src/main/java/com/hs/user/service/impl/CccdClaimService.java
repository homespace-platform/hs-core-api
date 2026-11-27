package com.hs.user.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.common.security.SecretCrypto;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.model.UserKycDetails;
import com.hs.user.repository.UserKycDetailsRepository;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims CCCD for VNPT verification under a shared lock.
 */
@Service
@RequiredArgsConstructor
public class CccdClaimService {

    private final UserRepository userRepository;
    private final UserKycDetailsRepository detailsRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockVerification(String userId) {
        // Serialize finalization before locking evidence rows.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimInCurrentTransaction(String userId, String cccd) {
        return claim(userId, cccd);
    }

    private boolean claim(String userId, String cccd) {
        // Serialize competing claims before an ORM unique violation; lock on the hash, never the plain CCCD.
        String hash = SecretCrypto.get().cccdHash(cccd);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", hash);
        if (!userRepository.existsById(userId)) {
            throw new AppException(UserErrorCode.USER_NOT_EXISTED);
        }
        UserKycDetails details = detailsRepository.findById(userId).orElseGet(() -> {
            var row = new UserKycDetails();
            row.setUserId(userId);
            return row;
        });

        if (hash.equals(details.getCccdHash())) {
            return true;
        }
        if (detailsRepository.existsByCccdHashAndUserIdNot(hash, userId)) {
            return false;
        }

        details.setCccd(cccd);
        detailsRepository.saveAndFlush(details);
        return true;
    }
}
