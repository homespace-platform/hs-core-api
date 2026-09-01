package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.dto.PageResponse;
import com.hs.listing.dto.request.*;
import com.hs.listing.dto.response.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.ListingStatus;
import com.hs.listing.repository.ListingRepository;
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

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ListingQueryService {
    public static final int MY_LISTING_PAGE_SIZE = 10;

    private final ListingRepository listingRepository;
    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final StorageProperties storageProperties;

    @Transactional(readOnly = true)
    public PageResponse<MyListingSummaryResponse> getMyListings(
            String ownerId, int page, ListingStatus status, String keyword) {
        requireAuthentication(ownerId);
        var pageable = PageRequest.of(
                page - 1,
                MY_LISTING_PAGE_SIZE,
                Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        Specification<Listing> specification = (root, query, cb) -> cb.and(
                cb.equal(root.get("ownerId"), ownerId), cb.isTrue(root.get("active")));
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern = "%" + keyword.trim().toLowerCase() + "%";
            specification = specification.and((root, query, cb) ->
                    cb.like(cb.lower(root.get("title")), pattern));
        }
        return new PageResponse<>(listingRepository.findAll(specification, pageable)
                .map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public ListingDetailResponse getById(String viewerId, String listingId) {
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> new AppException(ErrorCode.ROUTE_NOT_FOUND));
        boolean owner = viewerId != null && viewerId.equals(listing.getOwnerId());
        boolean publicListing = listing.getStatus() == ListingStatus.PUBLISHED;
        if (!owner && !publicListing) throw new AppException(ErrorCode.ROUTE_NOT_FOUND);
        return toDetail(listing);
    }

    private MyListingSummaryResponse toSummary(Listing listing) {
        ListingMedia cover = listing.getMedia().stream()
                .filter(ListingMedia::isCover)
                .filter(media -> media.getMediaType() == com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE)
                .findFirst()
                .orElseGet(() -> listing.getMedia().stream()
                        .filter(media -> media.getMediaType() == com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE)
                        .min(Comparator.comparing(ListingMedia::getSortOrder))
                        .orElse(null));
        String fullAddress = addressRepository.findByListingIdAndActiveTrue(listing.getId())
                .map(address -> address.getFullAddress())
                .orElse(null);
        return new MyListingSummaryResponse(
                listing.getId(), listing.getTitle(), listing.getCategory(), listing.getSubtype(), listing.getStatus(),
                listing.getAvailableFrom(), listing.getAreaM2(), listing.getPriceAmount(), listing.getCurrency(),
                listing.getPriceUnit(), listing.isNegotiable(), cover == null ? null : publicUrl(cover.getStorageObject()),
                cover == null ? null : cover.getStorageObject().getId(), listing.getMedia().size(), fullAddress,
                listing.getStatusReason(), listing.getSubmittedAt(), listing.getPublishedAt(), listing.getExpiresAt(),
                listing.getCreatedAt(), listing.getUpdatedAt(),
                listing.getViewCount() != null ? listing.getViewCount() : 0L);
    }

    public ListingDetailResponse toDetail(Listing listing) {
        var amenities = listing.getAmenities().stream()
                .sorted(Comparator.comparing(Amenity::getSortOrder).thenComparing(Amenity::getCode))
                .map(item -> new ListingOptionItemResponse(item.getCode(), item.getName(), item.getSortOrder()))
                .toList();
        var furnishings = listing.getFurnishings().stream()
                .sorted(Comparator.comparing(FurnishingItem::getSortOrder).thenComparing(FurnishingItem::getCode))
                .map(item -> new ListingOptionItemResponse(item.getCode(), item.getName(), item.getSortOrder()))
                .toList();
        var customAmenities = listing.getCustomAmenities().stream()
                .map(ListingCustomAmenity::getName)
                .sorted()
                .toList();
        var charges = listing.getCharges().stream()
                .sorted(Comparator.comparing(ListingCharge::getSortOrder))
                .map(this::toCharge)
                .toList();
        var media = listing.getMedia().stream()
                .sorted(Comparator.comparing(ListingMedia::getSortOrder))
                .map(this::toMedia)
                .toList();
        var address = addressRepository.findByListingIdAndActiveTrue(listing.getId())
                .map(ListingAddressResponse::from)
                .orElse(null);
        var viewingDays = listing.getViewingDays() == null
                ? List.<java.time.DayOfWeek>of()
                : listing.getViewingDays().stream().sorted().toList();
        var viewingSlots = listing.getViewingSlots() == null
                ? List.<com.hs.listing.model.constant.ListingEnums.ViewingSlot>of()
                : listing.getViewingSlots().stream().sorted().toList();
        var owner = userRepository.findById(listing.getOwnerId())
                .map(ListingOwnerResponse::from)
                .orElse(null);
        return new ListingDetailResponse(
                listing.getId(), listing.getOwnerId(), listing.getTitle(), listing.getDescription(),
                listing.getCategory(), listing.getSubtype(), listing.getRentalMode(), listing.getStatus(),
                listing.getAvailableFrom(), listing.getAreaM2(), toPricing(listing),
                toApartment(listing.getApartmentDetail()), toHouse(listing.getHouseDetail()),
                toOffice(listing.getOfficeDetail()), toCommercial(listing.getCommercialDetail()),
                toRoom(listing.getRoomDetail()), amenities, customAmenities, furnishings, charges,
                address, owner, media, viewingDays, viewingSlots,
                Boolean.TRUE.equals(listing.getActive()), listing.getStatusReason(), listing.getSubmittedAt(),
                listing.getPublishedAt(), listing.getExpiresAt(), listing.getStatusChangedAt(),
                listing.getStatusChangedBy(), listing.getVersion(),
                listing.getCreatedAt(), listing.getUpdatedAt(), listing.getCreatedBy(), listing.getUpdatedBy(),
                listing.getViewCount() != null ? listing.getViewCount() : 0L);
    }

    private ListingPricingRequest toPricing(Listing listing) {
        return new ListingPricingRequest(
                listing.getPriceAmount(), listing.getCurrency(), listing.getPriceUnit(), listing.isNegotiable(),
                listing.getDepositType(), listing.getDepositAmount(), listing.getDepositMonths(),
                listing.getPaymentCycle(), listing.getMinimumLeaseMonths(), listing.isManagementFeeIncluded(),
                listing.getVatIncluded());
    }

    private ApartmentDetailRequest toApartment(ListingApartmentDetail d) {
        return d == null ? null : new ApartmentDetailRequest(
                d.getProjectName(), d.getBuildingBlock(), d.getUnitCode(), d.getFloorNumber(),
                d.getBuildingTotalFloors(), d.getBedroomCount(), d.getBathroomCount(), d.getLivingRoomCount(),
                d.getKitchenCount(), d.getFurnishingStatus(), d.getMainDoorDirection(), d.getBalconyDirection(),
                d.getViewDescription(), d.getMaxOccupants(), d.getLegalStatus());
    }

    private HouseDetailRequest toHouse(ListingHouseDetail d) {
        return d == null ? null : new HouseDetailRequest(
                d.getLandAreaM2(), d.getFrontageWidthM(), d.getLengthM(), d.getAccessRoadWidthM(),
                d.getFrontageCount(), d.getTotalFloors(), d.getBedroomCount(), d.getBathroomCount(),
                d.getLivingRoomCount(), d.getKitchenCount(), d.getHasRooftop(), d.getHasGarage(), d.getAccessType(),
                d.getMaxOccupants(), d.getMaxVehicles(), d.getFurnishingStatus(), d.getLegalStatus(),
                d.getRentalScopeDescription(), d.getRentedFloorFrom(), d.getRentedFloorTo());
    }

    private OfficeDetailRequest toOffice(ListingOfficeDetail d) {
        if (d == null) return null;
        var hours = d.getOperatingHours().stream()
                .sorted(Comparator.comparing(ListingOfficeOperatingHour::getDayOfWeek))
                .map(hour -> new OfficeOperatingHourRequest(
                        hour.getDayOfWeek(), hour.getOpenTime(), hour.getCloseTime()))
                .toList();
        return new OfficeDetailRequest(
                d.getBuildingName(), d.getOfficeGrade(), d.getFloorNumber(), d.getHandoverStatus(),
                d.getExpectedSeats(), d.getMinimumDivisibleAreaM2(), d.getRestroomCount(), d.getRestroomType(),
                d.getPantryType(), d.getCarParkingCapacity(), d.getMotorbikeParkingCapacity(),
                d.getOperatingMode(), hours);
    }

    private CommercialDetailRequest toCommercial(ListingCommercialDetail d) {
        return d == null ? null : new CommercialDetailRequest(
                d.getPositionType(), d.getFrontageWidthM(), d.getLengthM(), d.getRoadWidthM(),
                d.getFrontageCount(), d.getRentedFloorCount(), d.getHasMezzanine(), d.getRestroomCount(),
                d.getAccessType(), d.getParkingType(), d.getHandoverStatus(), d.getHasThreePhasePower(),
                d.getHasStandardFireSafety(), d.getOperatingHoursDescription(), d.getRestrictedBusinesses(),
                d.getLoadingAreaDescription());
    }

    private RoomDetailRequest toRoom(ListingRoomDetail d) {
        return d == null ? null : new RoomDetailRequest(
                d.getRoomCode(), d.getFloorNumber(), d.getRestroomType(), d.getKitchenType(), d.getHasWindow(),
                d.getHasBalcony(), d.getHasMezzanine(), d.getFurnishingStatus(), d.getAccessType(),
                d.getAccessHoursType(), d.getElectricMeterType(), d.getWaterMeterType(), d.getMaxOccupants(),
                d.getMaxVehicles(), d.getParkingPolicy());
    }

    private ListingChargeRequest toCharge(ListingCharge charge) {
        return new ListingChargeRequest(
                charge.getChargeType(), charge.getBillingMethod(), charge.getAmount(), charge.getCurrency(),
                charge.getUnit(), charge.isIncludedInRent(), charge.getCustomName(), charge.getDescription(),
                charge.getSortOrder());
    }

    private ListingMediaResponse toMedia(ListingMedia media) {
        StorageObject object = media.getStorageObject();
        return new ListingMediaResponse(
                media.getId(), object.getId(), media.getMediaType(), media.getSortOrder(), media.isCover(),
                publicUrl(object), object.getContentType(), object.getSizeBytes());
    }

    private String publicUrl(StorageObject object) {
        if (object.getVisibility() != StorageVisibility.PUBLIC) return null;
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(object.getBucketName(), storageProperties.region(), object.getObjectKey());
    }

    private void requireAuthentication(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) throw new AppException(ErrorCode.UNAUTHENTICATED);
    }
}
