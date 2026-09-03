package com.hs.listing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class RentalHoldExpirationScheduler {

    private final RentalRequestService rentalRequestService;

    @Scheduled(fixedDelayString = "${rental.expiration-check-delay-ms}")
    public void expireHoldRequests() {
        int count = rentalRequestService.expirePendingHoldRequests(Instant.now());
        if (count > 0) {
            log.info("Expired {} rental hold request(s) and restored listing(s) to PUBLISHED", count);
        }
    }
}
