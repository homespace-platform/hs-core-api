package com.hs.listing.service;

import com.hs.listing.model.Listing;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;

@Service
@RequiredArgsConstructor
@Slf4j
public class ListingViewService {

    private static final String VIEW_KEY_PREFIX = "listing:view:";
    private static final Duration VIEW_COOLDOWN = Duration.ofMinutes(1);

    private final ListingRepository listingRepository;
    private final StringRedisTemplate redisTemplate;
    private final Environment environment;
    private final ListingViewHistoryService viewHistoryService;

    public record ViewRecordResult(long viewCount, boolean counted) {}

    @Transactional
    public ViewRecordResult recordView(String listingId, String viewerId, String clientIp) {
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId).orElse(null);
        if (listing == null || listing.getStatus() != ListingStatus.PUBLISHED) {
            return new ViewRecordResult(0L, false);
        }

        // Tự động ghi nhận lịch sử xem tin cho người dùng đã đăng nhập
        if (viewerId != null && !viewerId.isBlank()) {
            try {
                viewHistoryService.recordView(viewerId, listingId);
            } catch (Exception e) {
                log.warn("Failed to record view history for user {} on listing {}: {}", viewerId, listingId, e.getMessage());
            }
        }

        long currentCount = listing.getViewCount() != null ? listing.getViewCount() : 0L;

        // Nếu môi trường là DEV (SPRING_PROFILES_ACTIVE=dev): Cứ ấn vào là tăng view ngay
        if (isDevProfile()) {
            listingRepository.incrementViewCount(listingId);
            long newCount = currentCount + 1;
            log.info("[DEV] Incremented view directly for listing {}, new count: {}", listingId, newCount);
            return new ViewRecordResult(newCount, true);
        }

        // Môi trường khác DEV (Prod, Staging...): Áp dụng cơ chế chống spam bằng Redis TTL 1 phút
        String identifier = (viewerId != null && !viewerId.isBlank())
                ? "u:" + viewerId
                : "ip:" + (clientIp != null && !clientIp.isBlank() ? clientIp.trim() : "unknown");

        String redisKey = VIEW_KEY_PREFIX + listingId + ":" + identifier;

        boolean isEligible = true;
        try {
            if (redisTemplate != null) {
                Boolean isAbsent = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", VIEW_COOLDOWN);
                isEligible = Boolean.TRUE.equals(isAbsent);
            }
        } catch (Exception e) {
            log.warn("Redis view rate limit check failed for listing {}, proceeding with view: {}", listingId, e.getMessage());
        }

        if (!isEligible) {
            return new ViewRecordResult(currentCount, false);
        }

        listingRepository.incrementViewCount(listingId);
        long newCount = currentCount + 1;
        log.info("Incremented view for listing {} by viewer {}, new count: {}", listingId, identifier, newCount);

        return new ViewRecordResult(newCount, true);
    }

    private boolean isDevProfile() {
        if (environment == null) return false;
        return Arrays.asList(environment.getActiveProfiles()).contains("dev");
    }
}
