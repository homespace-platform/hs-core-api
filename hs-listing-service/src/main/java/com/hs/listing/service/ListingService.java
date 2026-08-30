package com.hs.listing.service;

import com.hs.listing.advice.ListingValidationException;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.common.advice.entity.AppException;
import com.hs.listing.dto.request.*;
import com.hs.listing.dto.response.CreateListingResponse;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import com.hs.listing.model.constant.ListingEnums.*;
import com.hs.listing.repository.*;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ListingService {
    private final ListingRepository listingRepository;
    private final AddressRepository addressRepository;
    private final StorageObjectRepository storageObjectRepository;
    private final AmenityRepository amenityRepository;
    private final FurnishingItemRepository furnishingItemRepository;

    @Transactional
    public CreateListingResponse upsert(String ownerId, CreateListingRequest r) {
        if (ownerId == null || ownerId.isBlank())
            throw error(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        validate(r);
        boolean updating = r.id() != null && !r.id().isBlank();
        Listing l = upsertTarget(ownerId, r.id());
        if (updating) {
            clearOwnedData(l);
            listingRepository.flush();
        }
        applyCommonFields(l, ownerId, r);
        attachDetail(l, r);
        attachCatalogs(l, r);
        attachCharges(l, r);
        attachMedia(l, r, ownerId);
        attachViewingSchedule(l, r);
        Listing saved = listingRepository.save(l);
        linkStorageObjects(saved);
        upsertAddress(saved.getId(), ownerId, r.addressSource());
        return new CreateListingResponse(saved.getId(), saved.getStatus(), saved.getTitle(), saved.getPublishedAt());
    }

    private Listing upsertTarget(String ownerId, String listingId) {
        if (listingId == null || listingId.isBlank())
            return new Listing();
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> error(404, "LISTING_NOT_FOUND", "Listing not found"));
        if (!ownerId.equals(listing.getOwnerId()))
            throw error(403, "LISTING_FORBIDDEN", "Listing belongs to another user");
        return listing;
    }

    private void applyCommonFields(Listing l, String ownerId, CreateListingRequest r) {
        ListingPricingRequest p = r.pricing();
        l.setOwnerId(ownerId);
        l.setTitle(r.title().trim());
        l.setDescription(r.description().trim());
        l.setCategory(r.category());
        l.setSubtype(r.subtype());
        l.setRentalMode(r.rentalMode());
        l.setStatus(ListingStatus.PUBLISHED);
        l.setAvailableFrom(r.availableFrom());
        l.setAreaM2(r.areaM2());
        l.setPriceAmount(p.amount());
        l.setCurrency(
                p.currency() == null || p.currency().isBlank() ? "VND" : p.currency().trim().toUpperCase(Locale.ROOT));
        l.setPriceUnit(p.unit());
        l.setNegotiable(p.negotiable());
        l.setDepositType(p.depositType());
        l.setDepositAmount(p.depositAmount());
        l.setDepositMonths(p.depositMonths());
        l.setPaymentCycle(p.paymentCycle());
        l.setMinimumLeaseMonths(p.minimumLeaseMonths());
        l.setManagementFeeIncluded(p.managementFeeIncluded());
        l.setVatIncluded(p.vatIncluded());
        if (l.getPublishedAt() == null)
            l.setPublishedAt(Instant.now());
        l.setActive(true);
    }

    private void clearOwnedData(Listing l) {
        l.setApartmentDetail(null);
        l.setHouseDetail(null);
        l.setOfficeDetail(null);
        l.setCommercialDetail(null);
        l.setRoomDetail(null);
        l.getMedia().clear();
        l.getCharges().clear();
        l.getCustomAmenities().clear();
        l.getAmenities().clear();
        l.getFurnishings().clear();
        l.getViewingDays().clear();
        l.getViewingSlots().clear();
    }

    private void attachViewingSchedule(Listing l, CreateListingRequest r) {
        if (r.viewingDays() != null)
            l.getViewingDays().addAll(r.viewingDays());
        if (r.viewingSlots() != null)
            l.getViewingSlots().addAll(r.viewingSlots());
    }

    private void linkStorageObjects(Listing listing) {
        for (ListingMedia media : listing.getMedia()) {
            StorageObject object = media.getStorageObject();
            object.setReferenceType("LISTING");
            object.setReferenceId(listing.getId());
        }
    }

    private void validate(CreateListingRequest r) {
        if (r.subtype().category() != r.category())
            throw error(409, "DETAIL_CATEGORY_CONFLICT", "subtype does not belong to category");
        int details = (r.apartmentDetail() != null ? 1 : 0) + (r.houseDetail() != null ? 1 : 0)
                + (r.officeDetail() != null ? 1 : 0) + (r.commercialDetail() != null ? 1 : 0)
                + (r.roomDetail() != null ? 1 : 0);
        if (details != 1 || switch (r.category()) {
            case APARTMENT -> r.apartmentDetail() == null;
            case HOUSE -> r.houseDetail() == null;
            case OFFICE -> r.officeDetail() == null;
            case COMMERCIAL_SPACE -> r.commercialDetail() == null;
            case ROOM -> r.roomDetail() == null;
        })
            throw error(409, "DETAIL_CATEGORY_CONFLICT", "Exactly one matching detail is required");
        Set<PriceUnit> units = switch (r.category()) {
            case APARTMENT, HOUSE -> Set.of(PriceUnit.MONTH);
            case ROOM -> Set.of(PriceUnit.ROOM_MONTH, PriceUnit.PERSON_MONTH);
            case OFFICE -> Set.of(PriceUnit.MONTH, PriceUnit.M2_MONTH, PriceUnit.SEAT_MONTH);
            case COMMERCIAL_SPACE -> Set.of(PriceUnit.MONTH, PriceUnit.M2_MONTH);
        };
        if (!units.contains(r.pricing().unit()))
            invalid("pricing.unit", "INVALID_FOR_CATEGORY");
        var p = r.pricing();
        boolean depositOk = switch (p.depositType()) {
            case FIXED_AMOUNT -> p.depositAmount() != null && p.depositMonths() == null;
            case MONTH_COUNT -> p.depositMonths() != null && p.depositAmount() == null;
            case NONE, NEGOTIABLE -> p.depositAmount() == null && p.depositMonths() == null;
        };
        if (!depositOk)
            invalid("pricing.depositType", "INVALID_DEPOSIT");
        if (r.category() == ListingCategory.APARTMENT && r.subtype() != ListingSubtype.APARTMENT_STUDIO
                && r.apartmentDetail().bedroomCount() == 0)
            invalid("apartmentDetail.bedroomCount", "MUST_BE_POSITIVE");
        if (r.category() == ListingCategory.OFFICE && r.subtype() == ListingSubtype.OFFICE_TRADITIONAL
                && (r.officeDetail().buildingName() == null || r.officeDetail().buildingName().isBlank()))
            invalid("officeDetail.buildingName", "REQUIRED");
        if ((r.category() == ListingCategory.APARTMENT || r.category() == ListingCategory.ROOM)
                && r.rentalMode() != RentalMode.WHOLE_UNIT)
            invalid("rentalMode", "INVALID_FOR_CATEGORY");
        if (r.category() == ListingCategory.HOUSE && r.rentalMode() == RentalMode.WHOLE_UNIT
                && (r.houseDetail().rentedFloorFrom() != null || r.houseDetail().rentedFloorTo() != null
                        || r.houseDetail().rentalScopeDescription() != null))
            invalid("houseDetail.rentedFloorFrom", "INVALID_FOR_RENTAL_MODE");
        if (r.category() == ListingCategory.OFFICE) {
            var o = r.officeDetail();
            var hours = o.operatingHours() == null ? List.<OfficeOperatingHourRequest>of() : o.operatingHours();
            if (o.operatingMode() == OperatingMode.CUSTOM_SCHEDULE && hours.isEmpty())
                invalid("officeDetail.operatingHours", "REQUIRED");
            if (o.operatingMode() != OperatingMode.CUSTOM_SCHEDULE && !hours.isEmpty())
                invalid("officeDetail.operatingHours", "INVALID_FOR_OPERATING_MODE");
            Set<java.time.DayOfWeek> days = new HashSet<>();
            for (var h : hours) {
                if (!days.add(h.dayOfWeek()))
                    invalid("officeDetail.operatingHours.dayOfWeek", "DUPLICATE");
                if (!h.openTime().isBefore(h.closeTime()))
                    invalid("officeDetail.operatingHours.closeTime", "MUST_BE_AFTER_OPEN_TIME");
            }
        }
        if (r.category() == ListingCategory.COMMERCIAL_SPACE
                && Set.of(ListingSubtype.COMMERCIAL_STORE, ListingSubtype.COMMERCIAL_SHOWROOM,
                        ListingSubtype.COMMERCIAL_SHOPHOUSE).contains(r.subtype())
                && r.commercialDetail().frontageWidthM() == null)
            invalid("commercialDetail.frontageWidthM", "REQUIRED");
        if (r.charges() != null)
            for (var c : r.charges()) {
                if (c.chargeType() == ChargeType.OTHER && (c.customName() == null || c.customName().isBlank()))
                    invalid("charges.customName", "REQUIRED");
                if (c.chargeType() == ChargeType.OVERTIME_AIR_CONDITIONING && r.category() != ListingCategory.OFFICE)
                    invalid("charges.chargeType", "INVALID_FOR_CATEGORY");
            }
        long images = r.media().stream().filter(m -> m.mediaType() == MediaType.IMAGE).count(),
                covers = r.media().stream().filter(ListingMediaRequest::cover).count();
        if (images == 0)
            invalid("media", "IMAGE_REQUIRED");
        if (covers > 1)
            invalid("media", "MULTIPLE_COVERS");
        if (r.media().stream().anyMatch(m -> m.cover() && m.mediaType() != MediaType.IMAGE))
            invalid("media.cover", "COVER_MUST_BE_IMAGE");
        var source = r.addressSource();
        if (source.type() == AddressSourceType.SAVED && (source.savedAddressId() == null || source.address() != null))
            invalid("addressSource", "INVALID_SAVED_SOURCE");
        if (source.type() == AddressSourceType.NEW && (source.address() == null || source.savedAddressId() != null))
            invalid("addressSource", "INVALID_NEW_SOURCE");
    }

    private void attachDetail(Listing l, CreateListingRequest r) {
        switch (r.category()) {
            case APARTMENT -> {
                var d = new ListingApartmentDetail();
                BeanUtils.copyProperties(r.apartmentDetail(), d);
                d.setListing(l);
                l.setApartmentDetail(d);
            }
            case HOUSE -> {
                var d = new ListingHouseDetail();
                BeanUtils.copyProperties(r.houseDetail(), d);
                d.setListing(l);
                l.setHouseDetail(d);
            }
            case OFFICE -> {
                var d = new ListingOfficeDetail();
                BeanUtils.copyProperties(r.officeDetail(), d, "operatingHours");
                d.setListing(l);
                if (r.officeDetail().operatingHours() != null)
                    for (var q : r.officeDetail().operatingHours()) {
                        var h = new ListingOfficeOperatingHour();
                        BeanUtils.copyProperties(q, h);
                        h.setListing(d);
                        d.getOperatingHours().add(h);
                    }
                l.setOfficeDetail(d);
            }
            case COMMERCIAL_SPACE -> {
                var d = new ListingCommercialDetail();
                BeanUtils.copyProperties(r.commercialDetail(), d);
                d.setListing(l);
                l.setCommercialDetail(d);
            }
            case ROOM -> {
                var d = new ListingRoomDetail();
                BeanUtils.copyProperties(r.roomDetail(), d);
                d.setListing(l);
                l.setRoomDetail(d);
            }
        }
    }

    private void attachCatalogs(Listing listing, CreateListingRequest request) {
        Map<String, Amenity> amenitiesByKey = new HashMap<>();
        for (Amenity amenity : amenityRepository.findAllByActiveTrue()) {
            amenitiesByKey.put(catalogKey(amenity.getCode()), amenity);
            amenitiesByKey.put(catalogKey(amenity.getName()), amenity);
        }
        if (request.amenityCodes() != null) {
            for (String value : request.amenityCodes()) {
                if (value == null || value.isBlank())
                    continue;
                Amenity amenity = amenitiesByKey.get(catalogKey(value));
                if (amenity == null) {
                    addCustomAmenity(listing, value);
                } else {
                    if (!amenity.getCategories().contains(request.category()))
                        invalid("amenityCodes", "INVALID_FOR_CATEGORY");
                    listing.getAmenities().add(amenity);
                }
            }
        }
        Map<String, FurnishingItem> furnishingsByKey = new HashMap<>();
        for (FurnishingItem item : furnishingItemRepository.findAllByActiveTrue()) {
            furnishingsByKey.put(catalogKey(item.getCode()), item);
            furnishingsByKey.put(catalogKey(item.getName()), item);
        }
        if (request.furnishingCodes() != null) {
            for (String value : request.furnishingCodes()) {
                if (value == null || value.isBlank())
                    continue;
                FurnishingItem item = furnishingsByKey.get(catalogKey(value));
                if (item == null)
                    throw error(404, "FURNISHING_NOT_FOUND", "Furnishing item not found: " + value);
                listing.getFurnishings().add(item);
            }
        }
        if (request.customAmenities() != null) {
            for (String name : request.customAmenities())
                addCustomAmenity(listing, name);
        }
    }

    private void addCustomAmenity(Listing listing, String name) {
        if (name == null || name.isBlank())
            return;
        var item = new ListingCustomAmenity();
        item.setListing(listing);
        item.setName(name.trim());
        listing.getCustomAmenities().add(item);
    }

    private String catalogKey(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private void attachCharges(Listing l, CreateListingRequest r) {
        if (r.charges() == null)
            return;
        for (var q : r.charges()) {
            var c = new ListingCharge();
            BeanUtils.copyProperties(q, c);
            c.setListing(l);
            if (c.getCurrency() == null)
                c.setCurrency("VND");
            l.getCharges().add(c);
        }
    }

    private void attachMedia(Listing l, CreateListingRequest r, String ownerId) {
        Set<String> seen = new HashSet<>();
        for (var q : r.media()) {
            if (!seen.add(q.storageObjectId()))
                invalid("media.storageObjectId", "DUPLICATE");
            StorageObject s = storageObjectRepository.findById(q.storageObjectId())
                    .orElseThrow(() -> error(404, "STORAGE_OBJECT_NOT_FOUND", "Storage object not found"));
            if (!ownerId.equals(s.getOwnerId()))
                throw error(403, "STORAGE_OBJECT_FORBIDDEN", "Storage object belongs to another user");
            if (s.getStatus() != StorageStatus.READY || !Boolean.TRUE.equals(s.getActive()))
                invalid("media.storageObjectId", "NOT_READY");
            StoragePurpose expectedPurpose = q.mediaType() == MediaType.IMAGE ? StoragePurpose.LISTING_IMAGE
                    : StoragePurpose.LISTING_VIDEO;
            if (s.getPurpose() != expectedPurpose)
                invalid("media.storageObjectId", "INVALID_STORAGE_PURPOSE");
            if (q.mediaType() == MediaType.IMAGE && !s.getContentType().startsWith("image/")
                    || q.mediaType() == MediaType.VIDEO && !s.getContentType().startsWith("video/"))
                invalid("media.mediaType", "CONTENT_TYPE_MISMATCH");
            var m = new ListingMedia();
            m.setListing(l);
            m.setStorageObject(s);
            m.setMediaType(q.mediaType());
            m.setSortOrder(q.sortOrder());
            m.setCover(q.cover());
            m.setMediaUrl(s.getObjectKey());
            l.getMedia().add(m);
        }
    }

    private void upsertAddress(String listingId, String ownerId, ListingAddressSourceRequest source) {
        Address target = addressRepository.findByListingIdAndActiveTrue(listingId).orElseGet(Address::new);
        if (source.type() == AddressSourceType.SAVED) {
            if (source.savedAddressId() == null)
                invalid("addressSource.savedAddressId", "REQUIRED");
            Address saved = addressRepository.findById(source.savedAddressId())
                    .orElseThrow(() -> error(404, "ADDRESS_NOT_FOUND", "Address not found"));
            if (saved.getUser() == null || !ownerId.equals(saved.getUser().getId()))
                throw error(403, "ADDRESS_FORBIDDEN", "Address belongs to another user");
            copyAddressFields(target, saved);
        } else {
            if (source.address() == null)
                invalid("addressSource.address", "REQUIRED");
            apply(target, source.address());
        }
        target.setUser(null);
        target.setListingId(listingId);
        target.setActive(true);
        addressRepository.save(target);
    }

    private void copyAddressFields(Address target, Address source) {
        target.setProvinceCode(source.getProvinceCode());
        target.setProvinceName(source.getProvinceName());
        target.setWardCode(source.getWardCode());
        target.setWardName(source.getWardName());
        target.setStreetLine(source.getStreetLine());
        target.setFullAddress(source.getFullAddress());
    }

    private void apply(Address a, ListingAddressRequest q) {
        a.setProvinceCode(q.provinceCode().trim());
        a.setProvinceName(q.provinceName().trim());
        a.setWardCode(q.wardCode().trim());
        a.setWardName(q.wardName().trim());
        a.setStreetLine(q.streetLine().trim());
        a.setFullAddress(q.fullAddress() == null || q.fullAddress().isBlank()
                ? String.join(", ", a.getStreetLine(), a.getWardName(), a.getProvinceName())
                : q.fullAddress().trim());
    }

    private Set<String> normalized(List<String> values) {
        if (values == null)
            return Set.of();
        Set<String> s = new LinkedHashSet<>();
        for (String v : values)
            if (v != null && !v.isBlank())
                s.add(v.trim().toUpperCase());
        return s;
    }

    private void invalid(String field, String code) {
        throw new ListingValidationException(field, code, validationMessage(field, code));
    }

    private String validationMessage(String field, String code) {
        return switch (code) {
            case "REQUIRED" -> field + " is required";
            case "INVALID_FOR_CATEGORY" -> field + " is not allowed for this listing category";
            default -> field + " is invalid";
        };
    }

    private AppException error(int status, String code, String message) {
        return new AppException(status, message, HttpStatus.valueOf(status));
    }
}
