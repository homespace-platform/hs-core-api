package com.hs.listing.service;

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
import com.hs.storage.service.StorageService;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ListingService {
    private final ListingRepository listingRepository;
    private final AddressRepository addressRepository;
    private final StorageObjectRepository storageObjectRepository;
    private final StorageService storageService;
    private final AmenityRepository amenityRepository;
    private final FurnishingItemRepository furnishingItemRepository;
    private final ListingStatusService listingStatusService;
    private final PropertyBranchRepository branchRepository;

    @Transactional
    public CreateListingResponse upsert(String ownerId, CreateListingRequest r) {
        if (ownerId == null || ownerId.isBlank())
            throw error(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
        boolean updating = r.id() != null && !r.id().isBlank();
        if (updating && r.duplicateSourceListingId() != null && !r.duplicateSourceListingId().isBlank()) {
            throw error(400, "DUPLICATE_INVALID_REQUEST", "Cannot specify duplicateSourceListingId when updating an existing listing");
        }
        Listing l = upsertTarget(ownerId, r.id());
        validate(r, l.getId() != null ? l : null);
        if (l.getStatus() == ListingStatus.VIOLATION)
            throw new AppException(com.hs.listing.advice.ListingErrorCode.LISTING_LOCKED_BY_VIOLATION);
        if (updating) {
            clearOwnedData(l);
            listingRepository.flush();
        }

        Listing sourceListing = null;
        if (r.duplicateSourceListingId() != null && !r.duplicateSourceListingId().isBlank()) {
            sourceListing = listingRepository.findByIdAndActiveTrue(r.duplicateSourceListingId())
                    .orElseThrow(() -> error(404, "SOURCE_LISTING_NOT_FOUND", "Tin gốc không tồn tại hoặc đã bị xóa"));
            if (!ownerId.equals(sourceListing.getOwnerId())) {
                throw error(403, "SOURCE_LISTING_FORBIDDEN", "Bạn không có quyền nhân bản tin đăng của người khác");
            }
            if (sourceListing.getStatus() == ListingStatus.VIOLATION) {
                throw new AppException(com.hs.listing.advice.ListingErrorCode.LISTING_LOCKED_BY_VIOLATION);
            }
        }

        applyCommonFields(l, ownerId, r);
        attachDetail(l, r);
        attachCatalogs(l, r);
        attachCharges(l, r);
        attachMedia(l, r, ownerId, sourceListing);
        attachViewingSchedule(l, r);
        listingStatusService.applySubmission(
                l, r.submissionAction(), ownerId, ListingStatusActorType.USER);
        Listing saved = listingRepository.save(l);
        linkStorageObjects(saved);
        upsertAddress(saved.getId(), ownerId, r.addressSource());
        return response(saved);
    }

    @Transactional
    public CreateListingResponse createByAdmin(String adminId, String ownerId, CreateListingRequest request) {
        requireActor(adminId);
        if (ownerId == null || ownerId.isBlank())
            throw error(400, "OWNER_REQUIRED", "ownerId is required");
        validate(request, null);
        Listing listing = new Listing();
        applyContent(listing, ownerId, request);
        listingStatusService.applySubmission(
                listing, request.submissionAction(), adminId, ListingStatusActorType.ADMIN);
        Listing saved = listingRepository.save(listing);
        linkStorageObjects(saved);
        upsertAddress(saved.getId(), ownerId, request.addressSource());
        return response(saved);
    }

    @Transactional
    public CreateListingResponse updateByAdmin(String adminId, String listingId, CreateListingRequest request) {
        requireActor(adminId);
        Listing listing = listingRepository.findByIdAndActiveTrue(listingId)
                .orElseThrow(() -> error(404, "LISTING_NOT_FOUND", "Listing not found"));
        validate(request, listing);
        ListingStatus preservedStatus = listing.getStatus();
        clearOwnedData(listing);
        listingRepository.flush();
        applyContent(listing, listing.getOwnerId(), request);
        listing.setStatus(preservedStatus);
        Listing saved = listingRepository.save(listing);
        linkStorageObjects(saved);
        upsertAddress(saved.getId(), saved.getOwnerId(), request.addressSource());
        return response(saved);
    }

    private void applyContent(Listing listing, String ownerId, CreateListingRequest request) {
        Listing sourceListing = null;
        if (request.duplicateSourceListingId() != null && !request.duplicateSourceListingId().isBlank()) {
            sourceListing = listingRepository.findByIdAndActiveTrue(request.duplicateSourceListingId())
                    .orElseThrow(() -> error(404, "SOURCE_LISTING_NOT_FOUND", "Tin gốc không tồn tại hoặc đã bị xóa"));
            if (sourceListing.getStatus() == ListingStatus.VIOLATION) {
                throw new AppException(com.hs.listing.advice.ListingErrorCode.LISTING_LOCKED_BY_VIOLATION);
            }
        }
        applyCommonFields(listing, ownerId, request);
        attachDetail(listing, request);
        attachCatalogs(listing, request);
        attachCharges(listing, request);
        attachMedia(listing, request, ownerId, sourceListing);
        attachViewingSchedule(listing, request);
    }

    private CreateListingResponse response(Listing listing) {
        return new CreateListingResponse(
                listing.getId(), listing.getStatus(), listing.getTitle(),
                listing.getSubmittedAt(), listing.getPublishedAt());
    }

    private void requireActor(String actorId) {
        if (actorId == null || actorId.isBlank())
            throw error(401, "AUTHENTICATION_REQUIRED", "Authentication is required");
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
        if (r.branchId() != null && !r.branchId().isBlank()) {
            PropertyBranch branch = branchRepository.findByIdAndOwnerIdAndActiveTrue(r.branchId(), ownerId)
                    .orElseThrow(() -> error(400, "INVALID_BRANCH", "Chi nhánh không tồn tại hoặc không thuộc sở hữu của bạn"));
            l.setBranchId(branch.getId());
        } else {
            l.setBranchId(null);
        }
        l.setTitle(r.title().trim());
        l.setDescription(r.description().trim());
        l.setCategory(r.category());
        l.setAvailableFrom(r.availableFrom());
        l.setAreaM2(r.areaM2());
        l.setMaxMotorbikeCount(r.maxMotorbikeCount());
        l.setMaxCarCount(r.maxCarCount());
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

    private void validate(CreateListingRequest r, Listing existing) {
        if (r.duplicateSourceListingId() != null && !r.duplicateSourceListingId().isBlank()) {
            if (r.id() != null && !r.id().isBlank()) {
                throw error(400, "DUPLICATE_INVALID_REQUEST", "Cannot specify duplicateSourceListingId when updating an existing listing");
            }
        }
        if (r.availableFrom() == null) {
            invalid("availableFrom", "REQUIRED");
        }
        if (r.availableFrom() != null && r.availableFrom().isBefore(LocalDate.now())) {
            boolean isUnchangedExisting = existing != null && existing.getAvailableFrom() != null
                    && existing.getAvailableFrom().equals(r.availableFrom());
            if (!isUnchangedExisting) {
                invalid("availableFrom", "CANNOT_BE_IN_PAST");
            }
        }
        if (r.category() != ListingCategory.HOUSE && r.category() != ListingCategory.APARTMENT && r.category() != ListingCategory.ROOM) {
            throw error(400, "UNSUPPORTED_CATEGORY", "Only HOUSE, APARTMENT, and ROOM are supported");
        }
        int details = (r.apartmentDetail() != null ? 1 : 0) + (r.houseDetail() != null ? 1 : 0)
                + (r.roomDetail() != null ? 1 : 0);
        if (r.officeDetail() != null || r.commercialDetail() != null || details != 1 || switch (r.category()) {
            case APARTMENT -> r.apartmentDetail() == null;
            case HOUSE -> r.houseDetail() == null;
            case ROOM -> r.roomDetail() == null;
            default -> true;
        })
            throw error(409, "DETAIL_CATEGORY_CONFLICT", "Exactly one matching detail is required");
        if (r.category() == ListingCategory.ROOM) {
            String roomCode = r.roomDetail().roomCode();
            if (roomCode == null || roomCode.isBlank())
                invalid("roomDetail.roomCode", "REQUIRED");
            if (roomCode.length() > 255)
                invalid("roomDetail.roomCode", "TOO_LONG");
        }
        Set<PriceUnit> units = switch (r.category()) {
            case APARTMENT, HOUSE -> Set.of(PriceUnit.MONTH);
            case ROOM -> Set.of(PriceUnit.ROOM_MONTH, PriceUnit.PERSON_MONTH, PriceUnit.MONTH);
            default -> throw error(400, "UNSUPPORTED_CATEGORY", "Unsupported category: " + r.category());
        };
        if (!units.contains(r.pricing().unit()))
            invalid("pricing.unit", "INVALID_PRICE_UNIT");
        if (r.pricing().amount() == null)
            invalid("pricing.amount", "REQUIRED");
        if (r.pricing().amount() != null && r.pricing().amount().compareTo(BigDecimal.ZERO) <= 0)
            invalid("pricing.amount", "MUST_BE_POSITIVE");
        var p = r.pricing();
        if (p.paymentCycle() != PaymentCycle.MONTHLY)
            invalid("pricing.paymentCycle", "ONLY_MONTHLY_ALLOWED");
        if (p.depositType() == DepositType.NEGOTIABLE)
            invalid("pricing.depositType", "NEGOTIABLE_NOT_ALLOWED");
        boolean depositOk = switch (p.depositType()) {
            case FIXED_AMOUNT -> p.depositAmount() != null && p.depositMonths() == null;
            case MONTH_COUNT -> p.depositMonths() != null && p.depositAmount() == null;
            case NONE -> p.depositAmount() == null && p.depositMonths() == null;
            case NEGOTIABLE -> false;
        };
        if (!depositOk)
            invalid("pricing.depositType", "INVALID_DEPOSIT");
        if (r.charges() != null)
            for (var c : r.charges()) {
                if (c.chargeType() == ChargeType.OTHER && (c.customName() == null || c.customName().isBlank()))
                    invalid("charges.customName", "REQUIRED");
                if (c.chargeType() == ChargeType.OVERTIME_AIR_CONDITIONING)
                    invalid("charges.chargeType", "INVALID_FOR_CATEGORY");
            }
        for (var m : r.media()) {
            boolean hasStorage = m.storageObjectId() != null && !m.storageObjectId().isBlank();
            boolean hasSource = m.sourceMediaId() != null && !m.sourceMediaId().isBlank();
            if (!hasStorage && !hasSource) {
                invalid("media", "STORAGE_OR_SOURCE_REQUIRED");
            }
            if (hasSource && (r.duplicateSourceListingId() == null || r.duplicateSourceListingId().isBlank())) {
                invalid("media.sourceMediaId", "SOURCE_LISTING_REQUIRED");
            }
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

            case ROOM -> {
                var src = Objects.requireNonNull(r.roomDetail(), "roomDetail");
                var d = new ListingRoomDetail();
                d.setRoomCode(src.roomCode().trim());
                d.setFloorNumber(src.floorNumber());
                d.setRestroomType(src.restroomType());
                d.setKitchenType(src.kitchenType());
                d.setHasWindow(src.hasWindow());
                // Map tường minh — tránh BeanUtils bỏ sót field record (balconyType)
                BalconyType balcony = src.balconyType() != null ? src.balconyType() : BalconyType.NONE;
                d.setBalconyType(balcony);
                d.setHasBalcony(balcony != BalconyType.NONE);
                d.setHasMezzanine(src.hasMezzanine());
                d.setFurnishingStatus(src.furnishingStatus());
                d.setAccessType(src.accessType());
                d.setAccessHoursType(src.accessHoursType());
                d.setElectricMeterType(src.electricMeterType());
                d.setWaterMeterType(src.waterMeterType());
                d.setMaxOccupants(src.maxOccupants());
                d.setMaxVehicles(src.maxVehicles());
                d.setParkingPolicy(src.parkingPolicy());
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
        if (request.customAmenities() != null) {
            for (String name : request.customAmenities())
                addCustomAmenity(listing, name);
        }
        attachFurnishings(listing, request);
    }

    /**
     * Bảng kiểm kê trang thiết bị bàn giao. Mỗi dòng có thể tham chiếu catalog
     * (itemCode) hoặc là tài sản nhập tay; assetName luôn được chốt lại trên
     * listing để hợp đồng render đúng tên tại thời điểm đăng tin.
     */
    private void attachFurnishings(Listing listing, CreateListingRequest request) {
        var rows = request.furnishings() == null ? List.<ListingFurnishingRequest>of() : request.furnishings();
        if (rows.isEmpty()) {
            if (requiresFurnishingInventory(request))
                invalid("furnishings", "REQUIRED");
            return;
        }
        Map<String, FurnishingItem> furnishingsByKey = new HashMap<>();
        for (FurnishingItem item : furnishingItemRepository.findAllByActiveTrue()) {
            furnishingsByKey.put(catalogKey(item.getCode()), item);
            furnishingsByKey.put(catalogKey(item.getName()), item);
        }
        int sortOrder = 0;
        for (ListingFurnishingRequest row : rows) {
            FurnishingItem item = null;
            if (row.itemCode() != null && !row.itemCode().isBlank()) {
                item = furnishingsByKey.get(catalogKey(row.itemCode()));
                if (item == null)
                    throw error(404, "FURNISHING_NOT_FOUND", "Furnishing item not found: " + row.itemCode());
                if (!item.getCategories().contains(request.category()))
                    invalid("furnishings.itemCode", "INVALID_FOR_CATEGORY");
            }
            String assetName = row.assetName() == null || row.assetName().isBlank()
                    ? (item == null ? null : item.getName())
                    : row.assetName().trim();
            if (assetName == null || assetName.isBlank())
                invalid("furnishings.assetName", "REQUIRED");
            var asset = new ListingFurnishingAsset();
            asset.setListing(listing);
            asset.setFurnishingItem(item);
            asset.setItemCode(item == null ? null : item.getCode());
            asset.setAssetName(assetName);
            asset.setQuantity(row.quantity());
            asset.setHandoverCondition(row.handoverCondition());
            asset.setConditionNote(
                    row.conditionNote() == null || row.conditionNote().isBlank() ? null : row.conditionNote().trim());
            asset.setSortOrder(sortOrder++);
            listing.getFurnishings().add(asset);
        }
    }

    /**
     * Tin bàn giao thô được phép để trống bảng thiết bị; mọi mức bàn giao có nội
     * thất đều phải kê khai để làm biên bản bàn giao trong hợp đồng.
     */
    private boolean requiresFurnishingInventory(CreateListingRequest r) {
        return switch (r.category()) {
            case APARTMENT -> isFurnished(r.apartmentDetail().furnishingStatus());
            case HOUSE -> isFurnished(r.houseDetail().furnishingStatus());
            case ROOM -> isFurnished(r.roomDetail().furnishingStatus());
            default -> false;
        };
    }

    private boolean isFurnished(FurnishingStatus status) {
        return status != null && status != FurnishingStatus.UNFURNISHED;
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
        if (l.getBranchId() != null && !l.getBranchId().isBlank()) {
            PropertyBranch branch = branchRepository.findByIdAndActiveTrue(l.getBranchId())
                    .orElseThrow(() -> error(400, "INVALID_BRANCH", "Chi nhánh không tồn tại hoặc đã bị ngừng hoạt động"));
            List<String> missing = BranchChargeMappingHelper.findMissingCharges(branch.getCategory(), branch.getDefaultCharges());
            if (!missing.isEmpty()) {
                throw new AppException(com.hs.listing.advice.ListingErrorCode.BRANCH_CHARGES_INCOMPLETE,
                        "Biểu phí chi nhánh '" + branch.getName() + "' chưa hoàn chỉnh (còn thiếu: " + String.join(", ", missing) + "). Vui lòng cập nhật đầy đủ biểu phí chi nhánh trước khi đăng tin.");
            }
            List<ListingCharge> branchListingCharges = BranchChargeMappingHelper.mapBranchChargesToListing(branch.getDefaultCharges(), l);
            l.getCharges().addAll(branchListingCharges);
            return;
        }

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

    private void attachMedia(Listing l, CreateListingRequest r, String ownerId, Listing sourceListing) {
        Set<String> seen = new HashSet<>();
        List<String> copiedS3Keys = new ArrayList<>();
        Map<String, ListingMedia> sourceMediaMap = (sourceListing != null && sourceListing.getMedia() != null)
                ? sourceListing.getMedia().stream().collect(Collectors.toMap(ListingMedia::getId, java.util.function.Function.identity()))
                : Collections.emptyMap();

        try {
            for (var q : r.media()) {
                boolean hasStorageId = q.storageObjectId() != null && !q.storageObjectId().isBlank();
                boolean hasSourceMediaId = q.sourceMediaId() != null && !q.sourceMediaId().isBlank();

                StorageObject storageObject;
                if (hasSourceMediaId) {
                    if (sourceListing == null) {
                        invalid("media.sourceMediaId", "SOURCE_LISTING_REQUIRED");
                    }
                    if (!seen.add("src:" + q.sourceMediaId())) {
                        invalid("media.sourceMediaId", "DUPLICATE");
                    }
                    ListingMedia srcMedia = sourceMediaMap.get(q.sourceMediaId());
                    if (srcMedia == null) {
                        throw error(400, "INVALID_SOURCE_MEDIA", "Source media does not belong to the source listing");
                    }
                    StorageObject srcObj = srcMedia.getStorageObject();
                    if (srcObj == null || !Boolean.TRUE.equals(srcObj.getActive()) || srcObj.getStatus() != StorageStatus.READY) {
                        invalid("media.sourceMediaId", "NOT_READY");
                    }
                    if (!ownerId.equals(srcObj.getOwnerId())) {
                        throw error(403, "STORAGE_OBJECT_FORBIDDEN", "Storage object belongs to another user");
                    }
                    if (srcMedia.getMediaType() != q.mediaType()) {
                        invalid("media.mediaType", "CONTENT_TYPE_MISMATCH");
                    }
                    StoragePurpose expectedPurpose = q.mediaType() == MediaType.IMAGE ? StoragePurpose.LISTING_IMAGE
                            : StoragePurpose.LISTING_VIDEO;

                    var copyResp = storageService.copyObject(
                            srcObj.getId(),
                            ownerId,
                            "LISTING",
                            l.getId(),
                            expectedPurpose);

                    storageObject = storageObjectRepository.findById(copyResp.id())
                            .orElseThrow(() -> error(500, "STORAGE_COPY_FAILED", "Failed to load copied storage object"));
                    copiedS3Keys.add(storageObject.getObjectKey());
                } else {
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
                    if ((q.mediaType() == MediaType.IMAGE && !s.getContentType().startsWith("image/"))
                            || (q.mediaType() == MediaType.VIDEO && !s.getContentType().startsWith("video/")))
                        invalid("media.mediaType", "CONTENT_TYPE_MISMATCH");
                    storageObject = s;
                }

                var m = new ListingMedia();
                m.setListing(l);
                m.setStorageObject(storageObject);
                m.setMediaType(q.mediaType());
                m.setSortOrder(q.sortOrder());
                m.setCover(q.cover());
                m.setMediaUrl(storageObject.getObjectKey());
                l.getMedia().add(m);
            }
        } catch (Exception e) {
            for (String key : copiedS3Keys) {
                try {
                    storageService.deleteS3ObjectDirect(key);
                } catch (Exception ex) {
                    // ignore cleanup error
                }
            }
            throw e;
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
        throw new AppException(
                422,
                field + " [" + code + "]: " + validationMessage(field, code),
                HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private String validationMessage(String field, String code) {
        return switch (code) {
            case "REQUIRED" -> field + " is required";
            case "INVALID_FOR_CATEGORY" -> field + " is not allowed for this listing category";
            case "CANNOT_BE_IN_PAST" -> "Ngày có thể vào thuê / bàn giao không được ở trong quá khứ";
            default -> field + " is invalid";
        };
    }

    private AppException error(int status, String code, String message) {
        return new AppException(status, message, HttpStatus.valueOf(status));
    }
}
