package com.hs.listing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListingExpirationScheduler {
    private final ListingStatusService listingStatusService;

    @Scheduled(fixedDelayString = "${listing.expiration-check-delay-ms:3600000}")
    public void expireListings() {
        int count = listingStatusService.expirePublishedListings(Instant.now());
        if (count > 0) log.info("Expired {} listing(s)", count);
    }
}
