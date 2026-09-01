package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingFavorite;
import com.hs.listing.repository.ListingFavoriteRepository;
import com.hs.listing.repository.ListingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingFavoriteService {

    private final ListingFavoriteRepository favoriteRepository;
    private final ListingRepository listingRepository;
    private final ListingPublicService listingPublicService;

    @Transactional
    public boolean toggleFavorite(String userId, String listingId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        Optional<ListingFavorite> existing = favoriteRepository.findByUserIdAndListing_Id(userId, listingId);
        if (existing.isPresent()) {
            favoriteRepository.delete(existing.get());
            log.info("User {} removed listing {} from favorites", userId, listingId);
            return false;
        }

        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));

        if (listing.getStatus() != com.hs.listing.model.constant.ListingStatus.PUBLISHED) {
            throw new AppException(ListingErrorCode.LISTING_NOT_FOUND);
        }

        ListingFavorite favorite = ListingFavorite.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .listing(listing)
                .build();
        favorite.setActive(true);

        favoriteRepository.save(favorite);
        log.info("User {} saved listing {} to favorites", userId, listingId);
        return true;
    }

    @Transactional
    public void removeFavorite(String userId, String listingId) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        favoriteRepository.deleteByUserIdAndListing_Id(userId, listingId);
    }

    @Transactional(readOnly = true)
    public List<String> getFavoriteListingIds(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return favoriteRepository.findFavoriteListingIdsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicListingSummaryResponse> getMyFavorites(String userId, int page, int size) {
        if (userId == null || userId.isBlank()) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        int pageSize = Math.min(Math.max(size, 1), 50);
        int pageIndex = Math.max(page - 1, 0);
        var pageable = PageRequest.of(pageIndex, pageSize);

        Page<ListingFavorite> favoritesPage = favoriteRepository.findAllByUserIdWithListing(userId, pageable);
        return new PageResponse<>(favoritesPage.map(fav -> listingPublicService.toSummary(fav.getListing())));
    }
}
