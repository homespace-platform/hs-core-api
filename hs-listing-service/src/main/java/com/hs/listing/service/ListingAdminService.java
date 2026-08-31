package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.dto.PageResponse;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.response.*;
import com.hs.listing.dto.request.AdminCreateListingRequest;
import com.hs.listing.dto.request.CreateListingRequest;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingMedia;
import com.hs.listing.model.constant.*;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingStatusHistoryRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class ListingAdminService {
    private final ListingRepository listingRepository;
    private final ListingStatusHistoryRepository historyRepository;
    private final ListingQueryService queryService;
    private final ListingStatusService statusService;
    private final ListingService listingService;
    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final StorageProperties storageProperties;

    @Transactional
    public CreateListingResponse create(String adminId, AdminCreateListingRequest request) {
        if (!userRepository.existsById(request.ownerId())) {
            throw new AppException(ListingErrorCode.LISTING_OWNER_NOT_FOUND);
        }
        return listingService.createByAdmin(adminId, request.ownerId(), request.listing());
    }

    @Transactional
    public CreateListingResponse update(String adminId, String listingId, CreateListingRequest request) {
        if (request.id() != null && !request.id().isBlank() && !listingId.equals(request.id())) {
            throw new AppException(ListingErrorCode.INVALID_LISTING_STATUS_TRANSITION);
        }
        return listingService.updateByAdmin(adminId, listingId, request);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminListingSummaryResponse> findAll(
            int page, int size, ListingStatus status, String keyword, String ownerId,
            ListingCategory category, LocalDate fromDate, LocalDate toDate, String sort) {
        Specification<Listing> specification = (root, query, cb) -> cb.isTrue(root.get("active"));
        if (status != null) specification = specification.and(
                (root, query, cb) -> cb.equal(root.get("status"), status));
        if (ownerId != null && !ownerId.isBlank()) specification = specification.and(
                (root, query, cb) -> cb.equal(root.get("ownerId"), ownerId.trim()));
        if (category != null) specification = specification.and(
                (root, query, cb) -> cb.equal(root.get("category"), category));
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            specification = specification.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("title")), pattern));
        }
        ZoneId zone = ZoneId.of("Asia/Ho_Chi_Minh");
        if (fromDate != null) {
            Instant from = fromDate.atStartOfDay(zone).toInstant();
            specification = specification.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (toDate != null) {
            Instant until = toDate.plusDays(1).atStartOfDay(zone).toInstant();
            specification = specification.and((root, query, cb) ->
                    cb.lessThan(root.get("createdAt"), until));
        }
        var pageable = PageRequest.of(page - 1, size, adminSort(sort));
        return new PageResponse<>(listingRepository.findAll(specification, pageable).map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public AdminListingDetailResponse getById(String listingId) {
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> new AppException(ListingErrorCode.LISTING_NOT_FOUND));
        var history = historyRepository.findAllByListingIdOrderByCreatedAtAsc(listingId).stream()
                .map(ListingStatusHistoryResponse::from)
                .toList();
        return new AdminListingDetailResponse(queryService.toDetail(listing), history);
    }

    @Transactional
    public ListingDetailResponse changeStatus(String adminId, String listingId, ListingStatus status, String reason) {
        return queryService.toDetail(statusService.changeByAdmin(adminId, listingId, status, reason));
    }

    private AdminListingSummaryResponse toSummary(Listing listing) {
        ListingMedia cover = listing.getMedia().stream()
                .filter(ListingMedia::isCover)
                .filter(media -> media.getMediaType() == ListingEnums.MediaType.IMAGE)
                .findFirst()
                .orElseGet(() -> listing.getMedia().stream()
                        .filter(media -> media.getMediaType() == ListingEnums.MediaType.IMAGE)
                        .min(Comparator.comparing(ListingMedia::getSortOrder))
                        .orElse(null));
        String address = addressRepository.findByListingIdAndActiveTrue(listing.getId())
                .map(item -> item.getFullAddress()).orElse(null);
        ListingOwnerResponse owner = userRepository.findById(listing.getOwnerId())
                .map(ListingOwnerResponse::from).orElse(null);
        return new AdminListingSummaryResponse(
                listing.getId(), listing.getTitle(), listing.getCategory(), listing.getSubtype(),
                listing.getStatus(), listing.getStatusReason(), listing.getPriceAmount(), listing.getCurrency(),
                listing.getPriceUnit(), cover == null ? null : publicUrl(cover.getStorageObject()), address, owner,
                listing.getSubmittedAt(), listing.getPublishedAt(), listing.getExpiresAt(),
                listing.getStatusChangedAt(), listing.getStatusChangedBy(),
                listing.getCreatedAt(), listing.getUpdatedAt());
    }

    private Sort adminSort(String value) {
        String[] parts = value == null ? new String[0] : value.split(",", 2);
        String property = parts.length == 0 || parts[0].isBlank() ? "submittedAt" : parts[0].trim();
        if (!java.util.Set.of("submittedAt", "publishedAt", "expiresAt", "createdAt", "updatedAt", "title")
                .contains(property)) {
            property = "submittedAt";
        }
        Sort.Direction direction = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private String publicUrl(StorageObject object) {
        if (object.getVisibility() != StorageVisibility.PUBLIC) return null;
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(object.getBucketName(), storageProperties.region(), object.getObjectKey());
    }
}
