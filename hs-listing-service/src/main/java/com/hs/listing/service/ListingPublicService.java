package com.hs.listing.service;

import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.PublicListingSearchRequest;
import com.hs.listing.dto.response.PublicListingSummaryResponse;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingMedia;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.MediaType;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ListingPublicService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> ALLOWED_SORTS = Set.of(
            "newest", "price_asc", "price_desc", "area_asc", "area_desc");

    private final ListingRepository listingRepository;
    private final AddressRepository addressRepository;
    private final StorageProperties storageProperties;

    @Transactional(readOnly = true)
    public PageResponse<PublicListingSummaryResponse> search(PublicListingSearchRequest request) {
        int size = Math.min(Math.max(request.size(), 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(request.page() - 1, 0), size, resolveSort(request.sort()));
        Specification<Listing> spec = buildSpecification(request);
        return new PageResponse<>(listingRepository.findAll(spec, pageable).map(this::toSummary));
    }

    // ── Specification builder ───────────────────────────────────────────

    private Specification<Listing> buildSpecification(PublicListingSearchRequest request) {
        // Base: only active + PUBLISHED
        Specification<Listing> spec = (root, query, cb) -> cb.and(
                cb.isTrue(root.get("active")),
                cb.equal(root.get("status"), ListingStatus.PUBLISHED));

        // Category
        if (request.category() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), request.category()));
        }

        // Subtype (only meaningful when category is also set or subtype itself implies category)
        if (request.subtype() != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("subtype"), request.subtype()));
        }

        // Price range
        if (request.priceMin() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("priceAmount"), request.priceMin()));
        }
        if (request.priceMax() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("priceAmount"), request.priceMax()));
        }

        // Area range
        if (request.areaMin() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("areaM2"), request.areaMin()));
        }
        if (request.areaMax() != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("areaM2"), request.areaMax()));
        }

        // Keyword search — title, description, or address
        if (request.keyword() != null && !request.keyword().isBlank()) {
            String pattern = "%" + request.keyword().trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> {
                var titlePred = cb.like(cb.lower(root.get("title")), pattern);
                var descPred = cb.like(cb.lower(root.get("description")), pattern);
                var subquery = query.subquery(String.class);
                var addrRoot = subquery.from(Address.class);
                subquery.select(addrRoot.get("listingId"))
                        .where(cb.and(
                                cb.equal(addrRoot.get("listingId"), root.get("id")),
                                cb.isTrue(addrRoot.get("active")),
                                cb.or(
                                        cb.like(cb.lower(addrRoot.get("fullAddress")), pattern),
                                        cb.like(cb.lower(addrRoot.get("streetLine")), pattern),
                                        cb.like(cb.lower(addrRoot.get("wardName")), pattern),
                                        cb.like(cb.lower(addrRoot.get("provinceName")), pattern)
                                )));
                return cb.or(titlePred, descPred, cb.exists(subquery));
            });
        }

        // Bedrooms — join appropriate detail table based on category
        if (request.bedrooms() != null) {
            spec = spec.and(bedroomSpec(request.category(), request.bedrooms()));
        }

        // Province / Ward — subquery on Address table (not a direct JPA relation on Listing)
        if (request.provinceCode() != null && !request.provinceCode().isBlank()) {
            spec = spec.and((root, query, cb) -> {
                var subquery = query.subquery(String.class);
                var addrRoot = subquery.from(Address.class);
                subquery.select(addrRoot.get("listingId"))
                        .where(cb.and(
                                cb.equal(addrRoot.get("listingId"), root.get("id")),
                                cb.isTrue(addrRoot.get("active")),
                                cb.equal(addrRoot.get("provinceCode"), request.provinceCode().trim())));
                return cb.exists(subquery);
            });
        }
        if (request.wardCode() != null && !request.wardCode().isBlank()) {
            spec = spec.and((root, query, cb) -> {
                var subquery = query.subquery(String.class);
                var addrRoot = subquery.from(Address.class);
                subquery.select(addrRoot.get("listingId"))
                        .where(cb.and(
                                cb.equal(addrRoot.get("listingId"), root.get("id")),
                                cb.isTrue(addrRoot.get("active")),
                                cb.equal(addrRoot.get("wardCode"), request.wardCode().trim())));
                return cb.exists(subquery);
            });
        }

        // Has video
        if (Boolean.TRUE.equals(request.hasVideo())) {
            spec = spec.and((root, query, cb) -> {
                var mediaJoin = root.join("media");
                return cb.equal(mediaJoin.get("mediaType"), MediaType.VIDEO);
            });
        }

        // Furnishing status (Apartment, House, Room)
        if (request.furnishingStatus() != null) {
            spec = spec.and((root, query, cb) -> {
                if (request.category() == ListingCategory.APARTMENT) {
                    return cb.equal(root.join("apartmentDetail").get("furnishingStatus"), request.furnishingStatus());
                } else if (request.category() == ListingCategory.HOUSE) {
                    return cb.equal(root.join("houseDetail").get("furnishingStatus"), request.furnishingStatus());
                } else if (request.category() == ListingCategory.ROOM) {
                    return cb.equal(root.join("roomDetail").get("furnishingStatus"), request.furnishingStatus());
                }
                return cb.conjunction();
            });
        }

        // Direction (Apartment main door)
        if (request.direction() != null && !request.direction().isBlank() && request.category() == ListingCategory.APARTMENT) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("apartmentDetail").get("mainDoorDirection"), request.direction().trim()));
        }

        // Office Grade (Office)
        if (request.officeGrade() != null && !request.officeGrade().isBlank() && request.category() == ListingCategory.OFFICE) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("officeDetail").get("officeGrade"), request.officeGrade().trim()));
        }

        // Position Type (Commercial)
        if (request.positionType() != null && request.category() == ListingCategory.COMMERCIAL_SPACE) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("commercialDetail").get("positionType"), request.positionType()));
        }

        // Restroom Type (Room)
        if (request.restroomType() != null && request.category() == ListingCategory.ROOM) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("roomDetail").get("restroomType"), request.restroomType()));
        }

        // Has Mezzanine (Commercial or Room)
        if (Boolean.TRUE.equals(request.hasMezzanine())) {
            spec = spec.and((root, query, cb) -> {
                if (request.category() == ListingCategory.COMMERCIAL_SPACE) {
                    return cb.isTrue(root.join("commercialDetail").get("hasMezzanine"));
                } else if (request.category() == ListingCategory.ROOM) {
                    return cb.isTrue(root.join("roomDetail").get("hasMezzanine"));
                }
                return cb.conjunction();
            });
        }

        // Has Rooftop (House)
        if (Boolean.TRUE.equals(request.hasRooftop()) && request.category() == ListingCategory.HOUSE) {
            spec = spec.and((root, query, cb) ->
                    cb.isTrue(root.join("houseDetail").get("hasRooftop")));
        }

        // Has Garage (House)
        if (Boolean.TRUE.equals(request.hasGarage()) && request.category() == ListingCategory.HOUSE) {
            spec = spec.and((root, query, cb) ->
                    cb.isTrue(root.join("houseDetail").get("hasGarage")));
        }

        return spec;
    }

    /**
     * Builds bedroom filter via JPA join to the correct detail table.
     * bedrooms=3 means "3 or more".
     */
    private Specification<Listing> bedroomSpec(ListingCategory category, int bedrooms) {
        return (root, query, cb) -> {
            // Determine which detail relationship to join
            String detailRelation;
            if (category == ListingCategory.APARTMENT) {
                detailRelation = "apartmentDetail";
            } else if (category == ListingCategory.HOUSE) {
                detailRelation = "houseDetail";
            } else {
                // Bedrooms not applicable for this category — no-op filter
                return cb.conjunction();
            }
            var detail = root.join(detailRelation);
            if (bedrooms >= 3) {
                return cb.greaterThanOrEqualTo(detail.get("bedroomCount"), 3);
            }
            return cb.equal(detail.get("bedroomCount"), bedrooms);
        };
    }

    // ── Sort resolver ───────────────────────────────────────────────────

    private Sort resolveSort(String sortValue) {
        String value = sortValue == null || !ALLOWED_SORTS.contains(sortValue) ? "newest" : sortValue;
        Sort primary = switch (value) {
            case "price_asc" -> Sort.by(Sort.Direction.ASC, "priceAmount");
            case "price_desc" -> Sort.by(Sort.Direction.DESC, "priceAmount");
            case "area_asc" -> Sort.by(Sort.Direction.ASC, "areaM2");
            case "area_desc" -> Sort.by(Sort.Direction.DESC, "areaM2");
            default -> Sort.by(Sort.Direction.DESC, "publishedAt");
        };
        // Secondary sort for stable ordering
        return primary.and(Sort.by(Sort.Direction.DESC, "publishedAt"));
    }

    // ── Mapping ─────────────────────────────────────────────────────────

    private PublicListingSummaryResponse toSummary(Listing listing) {
        // Cover image
        ListingMedia cover = listing.getMedia().stream()
                .filter(ListingMedia::isCover)
                .filter(m -> m.getMediaType() == MediaType.IMAGE)
                .findFirst()
                .orElseGet(() -> listing.getMedia().stream()
                        .filter(m -> m.getMediaType() == MediaType.IMAGE)
                        .min(Comparator.comparing(ListingMedia::getSortOrder))
                        .orElse(null));

        // Has video
        boolean hasVideo = listing.getMedia().stream()
                .anyMatch(m -> m.getMediaType() == MediaType.VIDEO);

        // Address
        Address address = addressRepository.findByListingIdAndActiveTrue(listing.getId()).orElse(null);

        // Bedroom count from the correct detail table
        Integer bedroomCount = extractBedroomCount(listing);

        return new PublicListingSummaryResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getCategory(),
                listing.getSubtype(),
                listing.getAreaM2(),
                listing.getPriceAmount(),
                listing.getCurrency(),
                listing.getPriceUnit(),
                listing.isNegotiable(),
                cover == null ? null : publicUrl(cover.getStorageObject()),
                hasVideo,
                address == null ? null : address.getFullAddress(),
                address == null ? null : address.getProvinceCode(),
                address == null ? null : address.getProvinceName(),
                address == null ? null : address.getWardCode(),
                address == null ? null : address.getWardName(),
                bedroomCount,
                listing.getPublishedAt(),
                listing.getAvailableFrom()
        );
    }

    private Integer extractBedroomCount(Listing listing) {
        if (listing.getApartmentDetail() != null) {
            return listing.getApartmentDetail().getBedroomCount();
        }
        if (listing.getHouseDetail() != null) {
            return listing.getHouseDetail().getBedroomCount();
        }
        return null;
    }

    private String publicUrl(StorageObject object) {
        if (object.getVisibility() != StorageVisibility.PUBLIC) return null;
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(object.getBucketName(), storageProperties.region(), object.getObjectKey());
    }
}
