package com.hs.user.service.impl;

import com.hs.common.advice.entity.AppException;
import com.hs.user.advice.entity.enums.UserErrorCode;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims CCCD under a shared lock; ADMIN uses its enclosing transaction, VNPT uses an isolated claim.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CccdClaimService {

    private final UserRepository userRepository;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockVerification(String userId) {
        // Serialize manual verification with VNPT finalization before either locks its evidence rows.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", userId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claimInCurrentTransaction(String userId, String cccd) {
        return claim(userId, cccd);
    }

    /**
     * @return true if this user now owns the CCCD; false if already taken by another account
     */
    public boolean tryClaim(String userId, String cccd) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> claim(userId, cccd)));
        } catch (DataIntegrityViolationException ex) {
            // Catch after the isolated transaction has rolled back, not inside a rollback-only TX.
            log.warn("CCCD claim race lost for user={}", userId);
            return false;
        }
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
