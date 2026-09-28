package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.dto.request.CreateBranchChargeRequest;
import com.hs.listing.dto.request.CreatePropertyBranchRequest;
import com.hs.listing.dto.response.BranchChargeResponse;
import com.hs.listing.dto.response.PropertyBranchResponse;
import com.hs.listing.model.Amenity;
import com.hs.listing.model.BranchCharge;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.user.model.Address;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PropertyBranchService {

    private final PropertyBranchRepository branchRepository;
    private final AmenityRepository amenityRepository;
    private final ListingRepository listingRepository;
    private final ParkingReservationService parkingReservationService;
    private final StorageObjectRepository storageObjectRepository;
    private final StorageProperties storageProperties;

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();

    private String generateBranchCode() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder("CN-");
            for (int j = 0; j < 5; j++) {
                sb.append(CODE_ALPHABET.charAt(SECURE_RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String candidate = sb.toString();
            if (!branchRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        return "CN-" + (System.currentTimeMillis() % 1000000);
    }

    @Transactional
    public PropertyBranchResponse createBranch(String ownerId, CreatePropertyBranchRequest request) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new AppException(401, "Xác thực không hợp lệ", HttpStatus.UNAUTHORIZED);
        }

        int motorbikeCap = request.getMotorbikeParkingCapacity() != null ? Math.max(0, request.getMotorbikeParkingCapacity()) : 0;
        int carCap = request.getCarParkingCapacity() != null ? Math.max(0, request.getCarParkingCapacity()) : 0;
        BranchChargeMappingHelper.validateBranchCharges(request.getDefaultCharges(), motorbikeCap, carCap);

        Address address = new Address();
        address.setStreetLine(request.getStreetLine() != null ? request.getStreetLine() : "");
        address.setWardCode(request.getWardCode() != null ? request.getWardCode() : "");
        address.setWardName(request.getWardName() != null ? request.getWardName() : "");
        address.setProvinceCode(request.getProvinceCode() != null ? request.getProvinceCode() : "");
        address.setProvinceName(request.getProvinceName() != null ? request.getProvinceName() : "");
        address.setFullAddress(request.getFullAddress().trim());

        String code = (request.getCode() != null && !request.getCode().trim().isBlank())
                ? request.getCode().trim().toUpperCase()
                : generateBranchCode();

        PropertyBranch branch = PropertyBranch.builder()
                .id(UUID.randomUUID().toString())
                .ownerId(ownerId)
                .name(request.getName().trim())
                .code(code)
                .category(request.getCategory())
                .address(address)
                .description(request.getDescription())
                .buildingRules(request.getBuildingRules())
                .motorbikeParkingCapacity(motorbikeCap)
                .carParkingCapacity(carCap)
                .totalUnits(0)
                .build();

        validateAndLinkCoverImage(branch, request.getCoverImageId(), ownerId);
        attachCharges(branch, request.getDefaultCharges());
        attachAmenities(branch, request.getBuildingAmenityCodes());

        PropertyBranch saved = branchRepository.save(branch);
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<PropertyBranchResponse> getMyBranches(String ownerId) {
        return branchRepository.findAllByOwnerIdAndActiveTrue(ownerId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public PropertyBranchResponse getBranchById(String id, String ownerId) {
        PropertyBranch branch = branchRepository.findByIdAndOwnerIdAndActiveTrue(id, ownerId)
                .orElseThrow(() -> new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));
        return mapToResponse(branch);
    }

    @Transactional
    public PropertyBranchResponse updateBranch(String id, String ownerId, CreatePropertyBranchRequest request) {
        PropertyBranch branch = branchRepository.findByIdAndOwnerIdAndActiveTrue(id, ownerId)
                .orElseThrow(() -> new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));

        int newMotorbikeCap = request.getMotorbikeParkingCapacity() != null ? Math.max(0, request.getMotorbikeParkingCapacity()) : 0;
        int newCarCap = request.getCarParkingCapacity() != null ? Math.max(0, request.getCarParkingCapacity()) : 0;

        int currentMotorbikeCap = branch.getMotorbikeParkingCapacity() != null ? branch.getMotorbikeParkingCapacity() : 0;
        if (newMotorbikeCap < currentMotorbikeCap) {
            int maxUsage = parkingReservationService.getMaxConcurrentUsage(branch.getId(), com.hs.listing.model.constant.VehicleType.MOTORBIKE);
            if (newMotorbikeCap < maxUsage) {
                throw new AppException(
                        ListingErrorCode.PARKING_CAPACITY_CHANGED,
                        String.format("Không thể giảm sức chứa xe máy xuống %d vì đang có %d chỗ được giữ/hoạt động đồng thời.",
                                newMotorbikeCap, maxUsage)
                );
            }
        }

        int currentCarCap = branch.getCarParkingCapacity() != null ? branch.getCarParkingCapacity() : 0;
        if (newCarCap < currentCarCap) {
            int maxUsage = parkingReservationService.getMaxConcurrentUsage(branch.getId(), com.hs.listing.model.constant.VehicleType.CAR);
            if (newCarCap < maxUsage) {
                throw new AppException(
                        ListingErrorCode.PARKING_CAPACITY_CHANGED,
                        String.format("Không thể giảm sức chứa ô tô xuống %d vì đang có %d chỗ được giữ/hoạt động đồng thời.",
                                newCarCap, maxUsage)
                );
            }
        }

        BranchChargeMappingHelper.validateBranchCharges(request.getDefaultCharges(), newMotorbikeCap, newCarCap);

        branch.setName(request.getName().trim());
        if (request.getCode() != null && !request.getCode().trim().isBlank()) {
            branch.setCode(request.getCode().trim().toUpperCase());
        } else if (branch.getCode() == null || branch.getCode().isBlank()) {
            branch.setCode(generateBranchCode());
        }
        branch.setCategory(request.getCategory());
        branch.setDescription(request.getDescription());
        branch.setBuildingRules(request.getBuildingRules());
        branch.setMotorbikeParkingCapacity(newMotorbikeCap);
        branch.setCarParkingCapacity(newCarCap);

        Address address = branch.getAddress();
        if (address == null) {
            address = new Address();
            branch.setAddress(address);
        }
        address.setStreetLine(request.getStreetLine() != null ? request.getStreetLine() : "");
        address.setWardCode(request.getWardCode() != null ? request.getWardCode() : "");
        address.setWardName(request.getWardName() != null ? request.getWardName() : "");
        address.setProvinceCode(request.getProvinceCode() != null ? request.getProvinceCode() : "");
        address.setProvinceName(request.getProvinceName() != null ? request.getProvinceName() : "");
        address.setFullAddress(request.getFullAddress().trim());

        validateAndLinkCoverImage(branch, request.getCoverImageId(), ownerId);

        branch.getDefaultCharges().clear();
        attachCharges(branch, request.getDefaultCharges());

        branch.getBuildingAmenities().clear();
        attachAmenities(branch, request.getBuildingAmenityCodes());

        PropertyBranch updated = branchRepository.save(branch);

        // Đồng bộ toàn bộ biểu phí mới sang các tin đăng đang hoạt động thuộc chi nhánh
        List<Listing> branchListings = listingRepository.findAllByBranchIdAndActiveTrue(updated.getId());
        for (Listing l : branchListings) {
            l.getCharges().clear();
            List<ListingCharge> newCharges = BranchChargeMappingHelper.mapBranchChargesToListing(updated.getDefaultCharges(), l);
            l.getCharges().addAll(newCharges);
        }
        if (!branchListings.isEmpty()) {
            listingRepository.saveAll(branchListings);
        }

        return mapToResponse(updated);
    }

    @Transactional
    public void deleteBranch(String id, String ownerId) {
        PropertyBranch branch = branchRepository.findByIdAndOwnerIdAndActiveTrue(id, ownerId)
                .orElseThrow(() -> new AppException(404, "Không tìm thấy chi nhánh", HttpStatus.NOT_FOUND));

        long activeRooms = listingRepository.countByBranchIdAndActiveTrue(id);
        if (activeRooms > 0) {
            throw new AppException(400, "Chi nhánh đang có " + activeRooms + " phòng/căn hộ. Vui lòng chuyển hoặc xóa hết các phòng trước khi xóa chi nhánh!", HttpStatus.BAD_REQUEST);
        }

        branch.setActive(false);
        branchRepository.save(branch);
    }

    private void validateAndLinkCoverImage(PropertyBranch branch, String coverImageId, String ownerId) {
        if (coverImageId == null || coverImageId.isBlank()) {
            branch.setCoverImageId(null);
            return;
        }
        StorageObject s = storageObjectRepository.findById(coverImageId)
                .orElseThrow(() -> new AppException(404, "Không tìm thấy ảnh bìa trong hệ thống lưu trữ", HttpStatus.NOT_FOUND));
        if (!ownerId.equals(s.getOwnerId())) {
            throw new AppException(403, "Ảnh bìa không thuộc quyền sở hữu của bạn", HttpStatus.FORBIDDEN);
        }
        if (s.getStatus() != StorageStatus.READY || !Boolean.TRUE.equals(s.getActive())) {
            throw new AppException(ListingErrorCode.BRANCH_COVER_IMAGE_INVALID, "Ảnh bìa chưa được tải lên thành công hoặc chưa sẵn sàng");
        }
        if (s.getContentType() == null || !s.getContentType().startsWith("image/")) {
            throw new AppException(ListingErrorCode.BRANCH_COVER_IMAGE_INVALID, "Tệp tải lên làm ảnh bìa phải là hình ảnh (JPEG, PNG, WebP)");
        }
        branch.setCoverImageId(coverImageId);
        s.setReferenceType("PROPERTY_BRANCH");
        s.setReferenceId(branch.getId());
    }

    private void attachCharges(PropertyBranch branch, List<CreateBranchChargeRequest> charges) {
        if (charges == null || charges.isEmpty()) return;
        int order = 1;
        for (CreateBranchChargeRequest c : charges) {
            BranchCharge bc = BranchCharge.builder()
                    .id(UUID.randomUUID().toString())
                    .branch(branch)
                    .chargeType(c.getChargeType())
                    .billingMethod(c.getBillingMethod())
                    .amount(c.getAmount())
                    .currency(c.getCurrency() != null ? c.getCurrency() : "VND")
                    .unit(c.getUnit())
                    .includedInRent(c.isIncludedInRent())
                    .customName(c.getCustomName())
                    .description(c.getDescription())
                    .sortOrder(c.getSortOrder() != null ? c.getSortOrder() : order++)
                    .build();
            branch.getDefaultCharges().add(bc);
        }
    }

    private void attachAmenities(PropertyBranch branch, List<String> codes) {
        if (codes == null || codes.isEmpty()) return;
        List<Amenity> amenities = amenityRepository.findAllByCodeInAndActiveTrue(codes);
        branch.getBuildingAmenities().addAll(amenities);
    }

    public PropertyBranchResponse mapToResponse(PropertyBranch b) {
        List<BranchChargeResponse> chargeResponses = b.getDefaultCharges() == null ? Collections.emptyList() :
                b.getDefaultCharges().stream()
                        .map(c -> BranchChargeResponse.builder()
                                .id(c.getId())
                                .chargeType(c.getChargeType())
                                .billingMethod(c.getBillingMethod())
                                .amount(c.getAmount())
                                .currency(c.getCurrency())
                                .unit(c.getUnit())
                                .includedInRent(c.isIncludedInRent())
                                .customName(c.getCustomName())
                                .description(c.getDescription())
                                .sortOrder(c.getSortOrder())
                                .build())
                        .collect(Collectors.toList());

        List<String> amenityCodes = b.getBuildingAmenities() == null ? Collections.emptyList() :
                b.getBuildingAmenities().stream()
                        .map(Amenity::getCode)
                        .collect(Collectors.toList());

        Address addr = b.getAddress();
        long count = listingRepository.countByBranchIdAndActiveTrue(b.getId());
        int totalUnits = count > 0 ? (int) count : (b.getTotalUnits() != null ? b.getTotalUnits() : 0);

        String coverImageUrl = null;
        if (b.getCoverImageId() != null) {
            StorageObject s = storageObjectRepository.findById(b.getCoverImageId()).orElse(null);
            if (s != null && s.getVisibility() == StorageVisibility.PUBLIC) {
                coverImageUrl = "https://%s.s3.%s.amazonaws.com/%s"
                        .formatted(s.getBucketName(), storageProperties.region(), s.getObjectKey());
            }
        }

        List<String> missingCharges = BranchChargeMappingHelper.findMissingCharges(b.getCategory(), b.getDefaultCharges());
        boolean isComplete = missingCharges.isEmpty();

        return PropertyBranchResponse.builder()
                .id(b.getId())
                .ownerId(b.getOwnerId())
                .name(b.getName())
                .code(b.getCode())
                .category(b.getCategory())
                .streetLine(addr != null ? addr.getStreetLine() : null)
                .wardCode(addr != null ? addr.getWardCode() : null)
                .wardName(addr != null ? addr.getWardName() : null)
                .provinceCode(addr != null ? addr.getProvinceCode() : null)
                .provinceName(addr != null ? addr.getProvinceName() : null)
                .fullAddress(addr != null ? addr.getFullAddress() : null)
                .description(b.getDescription())
                .buildingRules(b.getBuildingRules())
                .totalUnits(totalUnits)
                .activeListingsCount((int) count)
                .motorbikeParkingCapacity(b.getMotorbikeParkingCapacity() != null ? b.getMotorbikeParkingCapacity() : 0)
                .carParkingCapacity(b.getCarParkingCapacity() != null ? b.getCarParkingCapacity() : 0)
                .coverImageId(b.getCoverImageId())
                .coverImageUrl(coverImageUrl)
                .isComplete(isComplete)
                .missingCharges(missingCharges)
                .defaultCharges(chargeResponses)
                .buildingAmenityCodes(amenityCodes)
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .build();
    }
}

