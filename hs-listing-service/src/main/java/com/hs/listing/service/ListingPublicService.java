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
import com.hs.listing.dto.response.ListingOwnerResponse;
import com.hs.listing.model.constant.FurnishingStatus;
import com.hs.listing.model.constant.ListingEnums.PositionType;
import com.hs.listing.model.constant.ListingEnums.RestroomType;
import com.hs.user.repository.UserRepository;
import java.math.BigDecimal;
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
    private final UserRepository userRepository;
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

        // Bathroom count
        if (request.bathrooms() != null && request.bathrooms() > 0) {
            spec = spec.and((root, query, cb) -> {
                if (request.category() == ListingCategory.APARTMENT) {
                    var detail = root.join("apartmentDetail");
                    return request.bathrooms() >= 3 ? cb.greaterThanOrEqualTo(detail.get("bathroomCount"), 3)
                            : cb.equal(detail.get("bathroomCount"), request.bathrooms());
                } else if (request.category() == ListingCategory.HOUSE) {
                    var detail = root.join("houseDetail");
                    return request.bathrooms() >= 3 ? cb.greaterThanOrEqualTo(detail.get("bathroomCount"), 3)
                            : cb.equal(detail.get("bathroomCount"), request.bathrooms());
                } else if (request.category() == null) {
                    var aptDetail = root.join("apartmentDetail", jakarta.persistence.criteria.JoinType.LEFT);
                    var houseDetail = root.join("houseDetail", jakarta.persistence.criteria.JoinType.LEFT);
                    var aptMatch = request.bathrooms() >= 3 ? cb.greaterThanOrEqualTo(aptDetail.get("bathroomCount"), 3)
                            : cb.equal(aptDetail.get("bathroomCount"), request.bathrooms());
                    var houseMatch = request.bathrooms() >= 3 ? cb.greaterThanOrEqualTo(houseDetail.get("bathroomCount"), 3)
                            : cb.equal(houseDetail.get("bathroomCount"), request.bathrooms());
                    return cb.or(aptMatch, houseMatch);
                }
                return cb.conjunction();
            });
        }

        // Kitchen type (Room)
        if (request.kitchenType() != null && !request.kitchenType().isBlank() && request.category() == ListingCategory.ROOM) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("roomDetail").get("kitchenType"), request.kitchenType().trim()));
        }

        // Access type (House, Room, Commercial)
        if (request.accessType() != null && !request.accessType().isBlank()) {
            spec = spec.and((root, query, cb) -> {
                if (request.category() == ListingCategory.HOUSE) {
                    return cb.equal(root.join("houseDetail").get("accessType"), request.accessType().trim());
                } else if (request.category() == ListingCategory.ROOM) {
                    return cb.equal(root.join("roomDetail").get("accessType"), request.accessType().trim());
                } else if (request.category() == ListingCategory.COMMERCIAL_SPACE) {
                    return cb.equal(root.join("commercialDetail").get("accessType"), request.accessType().trim());
                }
                return cb.conjunction();
            });
        }

        // Legal status (Apartment, House)
        if (request.legalStatus() != null && !request.legalStatus().isBlank()) {
            spec = spec.and((root, query, cb) -> {
                if (request.category() == ListingCategory.APARTMENT) {
                    return cb.equal(root.join("apartmentDetail").get("legalStatus"), request.legalStatus().trim());
                } else if (request.category() == ListingCategory.HOUSE) {
                    return cb.equal(root.join("houseDetail").get("legalStatus"), request.legalStatus().trim());
                }
                return cb.conjunction();
            });
        }

        // Balcony direction (Apartment)
        if (request.balconyDirection() != null && !request.balconyDirection().isBlank() && request.category() == ListingCategory.APARTMENT) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.join("apartmentDetail").get("balconyDirection"), request.balconyDirection().trim()));
        }

        return spec;
    }

    /**
     * Builds bedroom filter via JPA join to the correct detail table.
     * bedrooms=3 means "3 or more".
     */
    private Specification<Listing> bedroomSpec(ListingCategory category, int bedrooms) {
        return (root, query, cb) -> {
            if (category == null) {
                var apt = root.join("apartmentDetail", jakarta.persistence.criteria.JoinType.LEFT);
                var house = root.join("houseDetail", jakarta.persistence.criteria.JoinType.LEFT);
                var aptMatch = bedrooms >= 3 ? cb.greaterThanOrEqualTo(apt.get("bedroomCount"), 3)
                        : cb.equal(apt.get("bedroomCount"), bedrooms);
                var houseMatch = bedrooms >= 3 ? cb.greaterThanOrEqualTo(house.get("bedroomCount"), 3)
                        : cb.equal(house.get("bedroomCount"), bedrooms);
                return cb.or(aptMatch, houseMatch);
            }
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

    public PublicListingSummaryResponse toSummary(Listing listing) {
        // All images sorted: cover first, then by sortOrder
        java.util.List<String> imageUrls = listing.getMedia().stream()
                .filter(m -> m.getMediaType() == MediaType.IMAGE)
                .sorted(Comparator.comparing(ListingMedia::isCover).reversed()
                        .thenComparing(ListingMedia::getSortOrder))
                .map(m -> publicUrl(m.getStorageObject()))
                .filter(java.util.Objects::nonNull)
                .toList();

        // Cover image
        ListingMedia cover = listing.getMedia().stream()
                .filter(ListingMedia::isCover)
                .filter(m -> m.getMediaType() == MediaType.IMAGE)
                .findFirst()
                .orElseGet(() -> listing.getMedia().stream()
                        .filter(m -> m.getMediaType() == MediaType.IMAGE)
                        .min(Comparator.comparing(ListingMedia::getSortOrder))
                        .orElse(null));
        String coverImageUrl = cover == null ? null : publicUrl(cover.getStorageObject());
        if (coverImageUrl == null && !imageUrls.isEmpty()) {
            coverImageUrl = imageUrls.get(0);
        }

        // Has video
        boolean hasVideo = listing.getMedia().stream()
                .anyMatch(m -> m.getMediaType() == MediaType.VIDEO);

        // Address
        Address address = addressRepository.findByListingIdAndActiveTrue(listing.getId()).orElse(null);

        // Bedroom & Bathroom counts from detail tables
        Integer bedroomCount = extractBedroomCount(listing);
        Integer bathroomCount = extractBathroomCount(listing);

        // Category-exclusive specs
        Integer floorNumber = extractFloorNumber(listing);
        Integer totalFloors = extractTotalFloors(listing);
        String restroomType = extractRestroomType(listing);
        Boolean hasMezzanine = extractHasMezzanine(listing);
        Boolean hasBalcony = extractHasBalcony(listing);
        Boolean hasWindow = extractHasWindow(listing);
        Boolean hasRooftop = listing.getHouseDetail() != null ? listing.getHouseDetail().getHasRooftop() : null;
        Boolean hasGarage = listing.getHouseDetail() != null ? listing.getHouseDetail().getHasGarage() : null;
        Integer expectedSeats = listing.getOfficeDetail() != null ? listing.getOfficeDetail().getExpectedSeats() : null;
        String officeGrade = listing.getOfficeDetail() != null ? listing.getOfficeDetail().getOfficeGrade() : null;
        BigDecimal frontageWidthM = listing.getHouseDetail() != null ? listing.getHouseDetail().getFrontageWidthM()
                : (listing.getCommercialDetail() != null ? listing.getCommercialDetail().getFrontageWidthM() : null);
        PositionType positionType = listing.getCommercialDetail() != null ? listing.getCommercialDetail().getPositionType() : null;
        FurnishingStatus furnishingStatus = extractFurnishingStatus(listing);

        // Owner info & listing count
        var ownerUser = listing.getOwnerId() != null ? userRepository.findById(listing.getOwnerId()).orElse(null) : null;
        var owner = ListingOwnerResponse.from(ownerUser);
        int ownerListingCount = listing.getOwnerId() != null
                ? (int) listingRepository.countByOwnerIdAndStatusAndActiveTrue(listing.getOwnerId(), ListingStatus.PUBLISHED)
                : 0;

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
                coverImageUrl,
                imageUrls,
                hasVideo,
                address == null ? null : address.getFullAddress(),
                address == null ? null : address.getProvinceCode(),
                address == null ? null : address.getProvinceName(),
                address == null ? null : address.getWardCode(),
                address == null ? null : address.getWardName(),
                bedroomCount,
                bathroomCount,
                floorNumber,
                totalFloors,
                restroomType,
                hasMezzanine,
                hasBalcony,
                hasWindow,
                hasRooftop,
                hasGarage,
                expectedSeats,
                officeGrade,
                frontageWidthM,
                positionType,
                furnishingStatus,
                listing.getOwnerId(),
                owner == null ? null : owner.displayName(),
                owner == null ? null : owner.avatarUrl(),
                ownerListingCount,
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

    private Integer extractBathroomCount(Listing listing) {
        if (listing.getApartmentDetail() != null) {
            return listing.getApartmentDetail().getBathroomCount();
        }
        if (listing.getHouseDetail() != null) {
            return listing.getHouseDetail().getBathroomCount();
        }
        if (listing.getOfficeDetail() != null) {
            return listing.getOfficeDetail().getRestroomCount();
        }
        if (listing.getCommercialDetail() != null) {
            return listing.getCommercialDetail().getRestroomCount();
        }
        if (listing.getRoomDetail() != null) {
            return listing.getRoomDetail().getRestroomType() == RestroomType.PRIVATE ? 1 : 0;
        }
        return null;
    }

    private Integer extractFloorNumber(Listing listing) {
        if (listing.getApartmentDetail() != null) return listing.getApartmentDetail().getFloorNumber();
        if (listing.getOfficeDetail() != null) return listing.getOfficeDetail().getFloorNumber();
        if (listing.getRoomDetail() != null) return listing.getRoomDetail().getFloorNumber();
        return null;
    }

    private Integer extractTotalFloors(Listing listing) {
        if (listing.getApartmentDetail() != null) return listing.getApartmentDetail().getBuildingTotalFloors();
        if (listing.getHouseDetail() != null) return listing.getHouseDetail().getTotalFloors();
        if (listing.getCommercialDetail() != null) return listing.getCommercialDetail().getRentedFloorCount();
        return null;
    }

    private String extractRestroomType(Listing listing) {
        if (listing.getRoomDetail() != null && listing.getRoomDetail().getRestroomType() != null) {
            return listing.getRoomDetail().getRestroomType().name();
        }
        if (listing.getOfficeDetail() != null) {
            return listing.getOfficeDetail().getRestroomType();
        }
        return null;
    }

    private Boolean extractHasMezzanine(Listing listing) {
        if (listing.getRoomDetail() != null) return listing.getRoomDetail().getHasMezzanine();
        if (listing.getCommercialDetail() != null) return listing.getCommercialDetail().getHasMezzanine();
        return null;
    }

    private Boolean extractHasBalcony(Listing listing) {
        if (listing.getRoomDetail() != null) return listing.getRoomDetail().getHasBalcony();
        if (listing.getApartmentDetail() != null) return listing.getApartmentDetail().getBalconyDirection() != null;
        return null;
    }

    private Boolean extractHasWindow(Listing listing) {
        if (listing.getRoomDetail() != null) return listing.getRoomDetail().getHasWindow();
        return null;
    }

    private FurnishingStatus extractFurnishingStatus(Listing listing) {
        if (listing.getApartmentDetail() != null) return listing.getApartmentDetail().getFurnishingStatus();
        if (listing.getHouseDetail() != null) return listing.getHouseDetail().getFurnishingStatus();
        if (listing.getRoomDetail() != null) return listing.getRoomDetail().getFurnishingStatus();
        return null;
    }

    @Transactional(readOnly = true)
    public long getOwnerListingCount(String ownerId) {
        return listingRepository.countByOwnerIdAndStatusAndActiveTrue(ownerId, ListingStatus.PUBLISHED);
    }

    private String publicUrl(StorageObject object) {
        if (object == null || object.getVisibility() != StorageVisibility.PUBLIC) return null;
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(object.getBucketName(), storageProperties.region(), object.getObjectKey());
    }
}
