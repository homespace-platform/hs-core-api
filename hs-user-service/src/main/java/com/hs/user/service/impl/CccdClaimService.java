package com.hs.user.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.model.User;
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
        // Serialize competing claims before an ORM unique violation can log the identifier.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", cccd);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

        if (cccd.equals(user.getCccd())) {
            return true;
        }
        if (userRepository.existsByCccdAndIdNot(cccd, userId)) {
            return false;
        }

        user.setCccd(cccd);
        userRepository.saveAndFlush(user);
        return true;
    }
}
