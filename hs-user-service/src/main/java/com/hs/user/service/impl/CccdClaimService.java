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
 * Claims CCCD in an isolated transaction so unique conflicts do not poison the webhook TX.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CccdClaimService {

    private final UserRepository userRepository;

    /**
     * @return true if this user now owns the CCCD; false if already taken by another account
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(String userId, String cccd) {
        if (userRepository.existsByCccdAndIdNot(cccd, userId)) {
            return false;
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_EXISTED));

        if (cccd.equals(user.getCccd())) {
            return true;
        }

        user.setCccd(cccd);
        try {
            userRepository.saveAndFlush(user);
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.warn("CCCD claim race lost for user={}", userId);
            return false;
        }
    }
}
