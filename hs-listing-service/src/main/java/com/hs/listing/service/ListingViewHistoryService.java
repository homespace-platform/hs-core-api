package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingViewHistory;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingViewHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingViewHistoryService {

    public static final int MAX_HISTORY_ITEMS = 40;

    private final ListingViewHistoryRepository historyRepository;
    private final ListingRepository listingRepository;
    private final ListingPublicService listingPublicService;

    @Transactional
    public void recordView(String userId, String listingId) {
        if (userId == null || userId.isBlank() || listingId == null || listingId.isBlank()) {
            return;
        }

        // Chỉ lưu lịch sử cho tin đăng đang hoạt động và đã duyệt (PUBLISHED)
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId).orElse(null);
        if (listing == null || listing.getStatus() != ListingStatus.PUBLISHED) {
            return;
        }

        Optional<ListingViewHistory> existing = historyRepository.findByUserIdAndListing_Id(userId, listingId);
        if (existing.isPresent()) {
            // Nếu đã từng xem -> Cập nhật lại timestamp thành thời gian xem mới nhất
            ListingViewHistory history = existing.get();
            history.setViewedAt(Instant.now());
            history.setActive(true);
            historyRepository.save(history);
            log.debug("Updated viewedAt timestamp for user {} listing {}", userId, listingId);
        } else {
            // Nếu xem lần đầu -> Tạo bản ghi mới
            ListingViewHistory history = ListingViewHistory.builder()
                    .id(UUID.randomUUID().toString())
                    .userId(userId)
                    .listing(listing)
                    .viewedAt(Instant.now())
                    .build();
            history.setActive(true);
            historyRepository.save(history);
            log.debug("Saved new view history for user {} listing {}", userId, listingId);

            // Giới hạn tối đa 40 tin: Nếu vượt quá 40 thì xóa các tin cũ nhất ngoài top 40
            long totalCount = historyRepository.countByUserIdAndActiveTrue(userId);
            if (totalCount > MAX_HISTORY_ITEMS) {
                List<String> top40Ids = historyRepository.findTopIdsByUserId(userId, PageRequest.of(0, MAX_HISTORY_ITEMS));
                if (!top40Ids.isEmpty()) {
                    historyRepository.deleteByUserIdAndIdNotIn(userId, top40Ids);
                    log.debug("Trimmed excess view history for user {}, retained {} items", userId, top40Ids.size());
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicListingSummaryResponse> getMyHistory(String userId, int page, int size) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        int pageSize = Math.min(Math.max(size, 1), MAX_HISTORY_ITEMS);
        int pageIndex = Math.max(page - 1, 0);
        var pageable = PageRequest.of(pageIndex, pageSize);

        Page<ListingViewHistory> historyPage = historyRepository.findAllByUserIdWithListing(userId, pageable);
        return new PageResponse<>(historyPage.map(vh -> listingPublicService.toSummary(vh.getListing())));
    }

    @Transactional(readOnly = true)
    public List<String> getHistoryListingIds(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        var pageable = PageRequest.of(0, MAX_HISTORY_ITEMS);
        return historyRepository.findListingIdsByUserId(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long getHistoryCount(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        return historyRepository.countActiveHistoryByUserId(userId);
    }

    @Transactional
    public void removeHistoryItem(String userId, String listingId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        historyRepository.deleteByUserIdAndListing_Id(userId, listingId);
        log.info("Removed listing {} from user {} view history", listingId, userId);
    }

    @Transactional
    public void clearMyHistory(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        historyRepository.deleteAllByUserId(userId);
        log.info("Cleared entire view history for user {}", userId);
    }

    @Transactional
    public int purgeExpiredHistory(int days) {
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        return historyRepository.deleteByViewedAtBefore(cutoff);
    }
}
