package com.hs.contract.service.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.dto.estimate.ExcludedChargeItem;
import com.hs.listing.dto.estimate.PredictableChargeItem;
import com.hs.listing.dto.snapshot.ListingSnapshotDto;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.listing.model.constant.PaymentCycle;
import com.hs.payment.dto.BankAccountSnapshotDto;
import com.hs.payment.model.PaymentRequest;
import com.hs.user.model.Address;
import com.hs.user.model.User;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Gom dữ liệu thật từ hồ sơ hai bên, tin đăng và yêu cầu thuê/thanh toán thành
 * các snapshot của hợp đồng.
 *
 * <p>
 * Ưu tiên sử dụng bản chụp bất biến {@code ListingSnapshotDto} từ
 * {@code RentalRequest.getListingSnapshot()}
 * và dữ liệu thanh toán từ {@code RentalPayment}. Nếu yêu cầu cũ chưa có
 * snapshot, thực hiện fallback an toàn.
 *
 * <p>
 * Tuyệt đối không đưa {@code branchId}, tên chi nhánh, mã chi nhánh hay sức
 * chứa xe của chi nhánh vào hợp đồng.
 */
@Slf4j
@Component
public class ContractDataBuilder {

    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final ObjectMapper objectMapper;

    public ContractDataBuilder(UserRepository userRepository, AddressRepository addressRepository) {
        this(userRepository, addressRepository, new ObjectMapper());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ContractDataBuilder(UserRepository userRepository, AddressRepository addressRepository,
            ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Tập hợp các snapshot của một revision hợp đồng (V2). */
    @Getter
    @Builder
    public static class ContractSnapshots {
        private Map<String, Object> landlord;
        private Map<String, Object> tenant;
        private Map<String, Object> property;
        private Map<String, Object> lease;
        private Map<String, Object> financial;
        private List<Map<String, Object>> charges;
        private List<Map<String, Object>> equipments;
        private Map<String, Object> meters;
        private Map<String, Object> initialPayment;
        private List<Map<String, Object>> amenities;
        private Map<String, Object> policies;
        private String specialTerms;
        @Builder.Default
        private int schemaVersion = 3;
    }

    public ContractSnapshots build(RentalRequest request, Listing listing) {
        return build(request, listing, (PaymentRequest) null);
    }

    public ContractSnapshots build(RentalRequest request, Listing listing, PaymentRequest payment) {
        int occupants = resolveOccupantCount(request);

        // 1. Phân giải snapshot bất biến của Listing
        ListingSnapshotDto listingSnapshot = resolveListingSnapshot(request, listing);

        BankAccountSnapshotDto payeeBank = parseBankSnapshot(payment != null ? payment.getPayeeBankAccountSnapshot() : null);
        BankAccountSnapshotDto payerBank = parseBankSnapshot(payment != null ? payment.getPayerBankAccountSnapshot() : null);

        // 2. Build từng nhóm snapshot
        Map<String, Object> landlord = buildLandlord(request != null ? request.getOwnerId() : null, payeeBank);
        Map<String, Object> tenant = buildTenant(request, payerBank);
        Map<String, Object> property = buildProperty(listingSnapshot, listing);
        Map<String, Object> lease = buildLease(request, listingSnapshot, listing);
        Map<String, Object> financial = buildFinancial(request, listingSnapshot, listing, payment);
        List<Map<String, Object>> charges = buildCharges(request, listingSnapshot, listing, occupants);
        List<Map<String, Object>> equipments = buildEquipments(listingSnapshot, listing);
        List<Map<String, Object>> amenities = buildAmenities(listingSnapshot, listing, request, equipments);
        Map<String, Object> initialPayment = buildInitialPayment(request, payment, financial, charges);
        Map<String, Object> policies = buildPolicies(listingSnapshot, request);
        Map<String, Object> meters = buildMeters(listingSnapshot, listing, occupants);

        String specialTerms = "";

        return ContractSnapshots.builder()
                .landlord(landlord)
                .tenant(tenant)
                .property(property)
                .lease(lease)
                .financial(financial)
                .charges(charges)
                .equipments(equipments)
                .meters(meters)
                .initialPayment(initialPayment)
                .amenities(amenities)
                .policies(policies)
                .specialTerms(specialTerms)
                .schemaVersion(3)
                .build();
    }

    private BankAccountSnapshotDto parseBankSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, BankAccountSnapshotDto.class);
        } catch (Exception e) {
            log.warn("Failed to parse bank account snapshot in ContractDataBuilder", e);
            return null;
        }
    }

    // --- Giải quyết Snapshot bất biến ---

    private ListingSnapshotDto resolveListingSnapshot(RentalRequest request, Listing listing) {
        if (request != null && isNotBlank(request.getListingSnapshot())) {
            try {
                return objectMapper.readValue(request.getListingSnapshot(), ListingSnapshotDto.class);
            } catch (Exception e) {
                log.warn("Failed to parse listingSnapshot for rentalRequestId={}, falling back to live listing",
                        request.getId(), e);
            }
        }

        if (listing != null) {
            log.warn("RentalRequest id={} has no valid listingSnapshot, falling back to live listing id={}",
                    request != null ? request.getId() : null, listing.getId());
            return createFallbackSnapshot(listing);
        }

        return ListingSnapshotDto.builder().build();
    }

    private ListingSnapshotDto createFallbackSnapshot(Listing listing) {
        String unitNumber = resolveUnitNumber(listing);
        String floor = resolveFloor(listing);
        String buildingName = resolveBuildingName(listing);
        String baseAddress = addressRepository.findByListingIdAndActiveTrue(listing.getId())
                .map(Address::getFullAddress)
                .orElse("");
        String fullAddress = joinNonBlank(", ", unitNumber, floor, buildingName, baseAddress);

        ListingSnapshotDto.ApartmentDetailSnapshot aptSnapshot = null;
        if (listing.getApartmentDetail() != null) {
            var a = listing.getApartmentDetail();
            aptSnapshot = ListingSnapshotDto.ApartmentDetailSnapshot.builder()
                    .projectName(a.getProjectName())
                    .buildingBlock(a.getBuildingBlock())
                    .unitCode(a.getUnitCode())
                    .floorNumber(a.getFloorNumber())
                    .buildingTotalFloors(a.getBuildingTotalFloors())
                    .bedroomCount(a.getBedroomCount())
                    .bathroomCount(a.getBathroomCount())
                    .livingRoomCount(a.getLivingRoomCount())
                    .kitchenCount(a.getKitchenCount())
                    .furnishingStatus(a.getFurnishingStatus() != null ? a.getFurnishingStatus().name() : null)
                    .mainDoorDirection(a.getMainDoorDirection())
                    .balconyDirection(a.getBalconyDirection())
                    .viewDescription(a.getViewDescription())
                    .maxOccupants(a.getMaxOccupants())
                    .legalStatus(a.getLegalStatus())
                    .build();
        }

        ListingSnapshotDto.HouseDetailSnapshot houseSnapshot = null;
        if (listing.getHouseDetail() != null) {
            var h = listing.getHouseDetail();
            houseSnapshot = ListingSnapshotDto.HouseDetailSnapshot.builder()
                    .landAreaM2(h.getLandAreaM2())
                    .frontageWidthM(h.getFrontageWidthM())
                    .lengthM(h.getLengthM())
                    .accessRoadWidthM(h.getAccessRoadWidthM())
                    .frontageCount(h.getFrontageCount())
                    .totalFloors(h.getTotalFloors())
                    .bedroomCount(h.getBedroomCount())
                    .bathroomCount(h.getBathroomCount())
                    .livingRoomCount(h.getLivingRoomCount())
                    .kitchenCount(h.getKitchenCount())
                    .hasRooftop(h.getHasRooftop())
                    .hasGarage(h.getHasGarage())
                    .accessType(h.getAccessType())
                    .maxOccupants(h.getMaxOccupants())
                    .maxVehicles(h.getMaxVehicles())
                    .furnishingStatus(h.getFurnishingStatus() != null ? h.getFurnishingStatus().name() : null)
                    .legalStatus(h.getLegalStatus())
                    .rentalScopeDescription(h.getRentalScopeDescription())
                    .rentedFloorFrom(h.getRentedFloorFrom())
                    .rentedFloorTo(h.getRentedFloorTo())
                    .build();
        }

        ListingSnapshotDto.RoomDetailSnapshot roomSnapshot = null;
        if (listing.getRoomDetail() != null) {
            var r = listing.getRoomDetail();
            roomSnapshot = ListingSnapshotDto.RoomDetailSnapshot.builder()
                    .roomCode(r.getRoomCode())
                    .floorNumber(r.getFloorNumber())
                    .restroomType(r.getRestroomType() != null ? r.getRestroomType().name() : null)
                    .kitchenType(r.getKitchenType() != null ? r.getKitchenType().name() : null)
                    .hasWindow(r.getHasWindow())
                    .balconyType(r.getBalconyType() != null ? r.getBalconyType().name() : null)
                    .hasMezzanine(r.getHasMezzanine())
                    .furnishingStatus(r.getFurnishingStatus() != null ? r.getFurnishingStatus().name() : null)
                    .accessType(r.getAccessType() != null ? r.getAccessType().name() : null)
                    .accessHoursType(r.getAccessHoursType() != null ? r.getAccessHoursType().name() : null)
                    .electricMeterType(r.getElectricMeterType() != null ? r.getElectricMeterType().name() : null)
                    .waterMeterType(r.getWaterMeterType() != null ? r.getWaterMeterType().name() : null)
                    .maxOccupants(r.getMaxOccupants())
                    .maxVehicles(r.getMaxVehicles())
                    .parkingPolicy(r.getParkingPolicy() != null ? r.getParkingPolicy().name() : null)
                    .build();
        }

        List<ListingSnapshotDto.AmenityItemSnapshot> amenitySnapshots = new ArrayList<>();
        if (listing.getAmenities() != null) {
            for (Amenity a : listing.getAmenities()) {
                if (a != null && a.getName() != null) {
                    amenitySnapshots.add(ListingSnapshotDto.AmenityItemSnapshot.builder()
                            .code(a.getCode())
                            .name(a.getName())
                            .scope("Riêng trong căn/phòng/nhà")
                            .sourceType("LISTING")
                            .build());
                }
            }
        }

        List<String> customAmenities = new ArrayList<>();
        if (listing.getCustomAmenities() != null) {
            for (var ca : listing.getCustomAmenities()) {
                if (ca != null && ca.getName() != null) {
                    customAmenities.add(ca.getName());
                }
            }
        }

        List<ListingSnapshotDto.FurnishingItemSnapshot> furnishingSnapshots = new ArrayList<>();
        if (listing.getFurnishings() != null) {
            for (var f : listing.getFurnishings()) {
                if (f != null) {
                    furnishingSnapshots.add(ListingSnapshotDto.FurnishingItemSnapshot.builder()
                            .itemCode(f.getItemCode())
                            .assetName(f.getAssetName())
                            .quantity(f.getQuantity())
                            .handoverCondition(
                                    f.getHandoverCondition() != null ? f.getHandoverCondition().name() : "GOOD")
                            .conditionNote(f.getConditionNote())
                            .build());
                }
            }
        }

        List<ListingSnapshotDto.ChargeItemSnapshot> chargeSnapshots = new ArrayList<>();
        if (listing.getCharges() != null) {
            for (ListingCharge c : listing.getCharges()) {
                if (c != null) {
                    chargeSnapshots.add(ListingSnapshotDto.ChargeItemSnapshot.builder()
                            .chargeType(c.getChargeType() != null ? c.getChargeType().name() : null)
                            .billingMethod(c.getBillingMethod() != null ? c.getBillingMethod().name() : null)
                            .amount(c.getAmount())
                            .currency(c.getCurrency())
                            .includedInRent(c.isIncludedInRent())
                            .customName(c.getCustomName())
                            .description(c.getDescription())
                            .build());
                }
            }
        }

        return ListingSnapshotDto.builder()
                .schemaVersion(1)
                .listingId(listing.getId())
                .listingCode(listing.getId() != null && listing.getId().length() >= 8
                        ? listing.getId().substring(0, 8).toUpperCase()
                        : listing.getId())
                .category(listing.getCategory())
                .categoryLabel(categoryLabel(listing.getCategory()))
                .title(listing.getTitle())
                .fullAddress(fullAddress)
                .unitNumber(unitNumber)
                .floor(floor)
                .buildingName(buildingName)
                .areaM2(listing.getAreaM2())
                .priceAmount(listing.getPriceAmount())
                .currency(listing.getCurrency())
                .priceUnit(listing.getPriceUnit() != null ? listing.getPriceUnit().name() : "MONTH")
                .paymentCycle(listing.getPaymentCycle() != null ? listing.getPaymentCycle().name() : "MONTHLY")
                .depositType(listing.getDepositType() != null ? listing.getDepositType().name() : "ONE_MONTH")
                .depositAmount(listing.getDepositAmount())
                .depositMonths(listing.getDepositMonths())
                .minimumLeaseMonths(listing.getMinimumLeaseMonths())
                .vatIncluded(listing.getVatIncluded())
                .managementFeeIncluded(listing.isManagementFeeIncluded())
                .maxOccupants(resolveMaxOccupants(listing))
                .maxVehicles(resolveMaxVehicles(listing))
                .maxMotorbikeCount(listing.getMaxMotorbikeCount())
                .maxCarCount(listing.getMaxCarCount())
                .rentalScopeDescription(resolveRentalScopeDescription(listing))
                .apartmentDetail(aptSnapshot)
                .houseDetail(houseSnapshot)
                .roomDetail(roomSnapshot)
                .amenities(amenitySnapshots)
                .customAmenities(customAmenities)
                .furnishings(furnishingSnapshots)
                .charges(chargeSnapshots)
                .capturedAt(Instant.now())
                .build();
    }

    // --- Bên A / Bên B ---

    private Map<String, Object> buildLandlord(String landlordId, BankAccountSnapshotDto bank) {
        Map<String, Object> map = new LinkedHashMap<>();
        User user = findUser(landlordId);

        map.put("fullName", fullNameOf(user, "Chủ nhà (Bên A)"));
        map.put("idNumber", nullToEmpty(user == null ? null : user.getCccd()));
        map.put("permanentAddress", permanentAddressOf(landlordId));
        map.put("phone", nullToEmpty(user == null ? null : user.getPhone()));
        map.put("email", nullToEmpty(user == null ? null : user.getEmail()));
        map.put("bankName", bank != null && isNotBlank(bank.bankName()) ? bank.bankName() : "");
        map.put("bankAccountNumber", bank != null && isNotBlank(bank.accountNumber()) ? bank.accountNumber() : "");
        map.put("bankAccountHolder", bank != null && isNotBlank(bank.accountHolderName()) ? bank.accountHolderName() : "");
        return map;
    }

    private Map<String, Object> buildTenant(RentalRequest request, BankAccountSnapshotDto bank) {
        Map<String, Object> map = new LinkedHashMap<>();
        String renterId = request != null ? request.getRenterId() : null;
        User user = findUser(renterId);

        map.put("fullName", firstNonBlank(request != null ? request.getRenterName() : null, fullNameOf(user, "Người thuê (Bên B)")));
        map.put("idNumber", nullToEmpty(user == null ? null : user.getCccd()));
        map.put("permanentAddress", permanentAddressOf(renterId));
        map.put("phone", firstNonBlank(request != null ? request.getRenterPhone() : null, user == null ? null : user.getPhone()));
        map.put("email", firstNonBlank(request != null ? request.getRenterEmail() : null, user == null ? null : user.getEmail()));
        map.put("occupantCount", resolveOccupantCount(request));
        map.put("motorbikeCount", (request != null && request.getMotorbikeCount() != null) ? request.getMotorbikeCount() : 0);
        map.put("carCount", (request != null && request.getCarCount() != null) ? request.getCarCount() : 0);
        map.put("bankName", bank != null && isNotBlank(bank.bankName()) ? bank.bankName() : "");
        map.put("bankAccountNumber", bank != null && isNotBlank(bank.accountNumber()) ? bank.accountNumber() : "");
        map.put("bankAccountHolder", bank != null && isNotBlank(bank.accountHolderName()) ? bank.accountHolderName() : "");
        return map;
    }

    // --- Bất động sản ---

    private Map<String, Object> buildProperty(ListingSnapshotDto snapshot, Listing liveListing) {
        Map<String, Object> map = new LinkedHashMap<>();

        String listingCode = snapshot.getListingCode() != null ? snapshot.getListingCode()
                : (liveListing != null && liveListing.getId() != null && liveListing.getId().length() >= 8
                        ? liveListing.getId().substring(0, 8).toUpperCase()
                        : "");

        String unitNumber = isNotBlank(snapshot.getUnitNumber()) ? snapshot.getUnitNumber()
                : (liveListing != null ? resolveUnitNumber(liveListing) : "");
        String floor = isNotBlank(snapshot.getFloor()) ? snapshot.getFloor()
                : (liveListing != null ? resolveFloor(liveListing) : "");
        String buildingName = isNotBlank(snapshot.getBuildingName()) ? snapshot.getBuildingName()
                : (liveListing != null ? resolveBuildingName(liveListing) : "");

        String fullAddress = isNotBlank(snapshot.getFullAddress()) ? snapshot.getFullAddress() : "";
        if (fullAddress.isBlank() && liveListing != null) {
            String baseAddress = addressRepository.findByListingIdAndActiveTrue(liveListing.getId())
                    .map(Address::getFullAddress)
                    .orElse("");
            fullAddress = joinNonBlank(", ", unitNumber, floor, buildingName, baseAddress);
        }

        BigDecimal areaM2 = snapshot.getAreaM2() != null ? snapshot.getAreaM2()
                : (liveListing != null ? liveListing.getAreaM2() : null);

        ListingCategory category = snapshot.getCategory() != null ? snapshot.getCategory()
                : (liveListing != null ? liveListing.getCategory() : null);

        String rentalScope = isNotBlank(snapshot.getRentalScopeDescription()) ? snapshot.getRentalScopeDescription()
                : (liveListing != null ? resolveRentalScopeDescription(liveListing) : "Theo thỏa thuận");

        Integer maxOccupants = resolveMaxOccupants(snapshot, liveListing);
        Integer maxVehicles = resolveMaxVehicles(snapshot, liveListing);

        map.put("listingCode", listingCode);
        map.put("rentalScope", rentalScope);
        map.put("fullAddress", fullAddress);
        map.put("areaText", areaM2 != null ? stripTrailingZeros(areaM2) + " m²" : "");
        map.put("propertyType", categoryLabel(category));
        map.put("category", category != null ? category.name() : "");
        map.put("unitNumber", unitNumber);
        map.put("floor", floor);
        map.put("buildingName", buildingName);
        map.put("maxOccupants", maxOccupants != null ? maxOccupants : "");
        map.put("maxVehicles", maxVehicles != null ? maxVehicles : "");

        if (snapshot.getApartmentDetail() != null) {
            var apt = snapshot.getApartmentDetail();
            map.put("projectName", nullToEmpty(apt.getProjectName()));
            map.put("buildingBlock", nullToEmpty(apt.getBuildingBlock()));
        } else if (snapshot.getHouseDetail() != null) {
            var h = snapshot.getHouseDetail();
            if (h.getTotalFloors() != null)
                map.put("totalFloors", String.valueOf(h.getTotalFloors()));
            if (h.getBedroomCount() != null)
                map.put("bedroomCount", h.getBedroomCount());
        }

        // Đặc điểm chi tiết theo loại hình (dùng cho #propertyFeaturesTable)
        List<Map<String, String>> features = buildPropertyFeatures(snapshot, liveListing);
        map.put("features", features);

        return map;
    }

    private List<Map<String, String>> buildPropertyFeatures(ListingSnapshotDto snapshot, Listing liveListing) {
        List<Map<String, String>> rows = new ArrayList<>();

        if (snapshot.getApartmentDetail() != null) {
            var apt = snapshot.getApartmentDetail();
            addFeature(rows, "Tên dự án", apt.getProjectName());
            addFeature(rows, "Tòa / Block", apt.getBuildingBlock());
            addFeature(rows, "Mã căn hộ", apt.getUnitCode());
            addFeature(rows, "Tầng số", apt.getFloorNumber() != null ? "Tầng " + apt.getFloorNumber() : null);
            addFeature(rows, "Tổng số tầng tòa nhà",
                    apt.getBuildingTotalFloors() != null ? apt.getBuildingTotalFloors() + " tầng" : null);
            addFeature(rows, "Số người tối đa", apt.getMaxOccupants() != null ? apt.getMaxOccupants() + " người" : null);
            addFeature(rows, "Số phòng ngủ", apt.getBedroomCount() != null ? apt.getBedroomCount() + " phòng" : null);
            addFeature(rows, "Số phòng tắm / WC",
                    apt.getBathroomCount() != null ? apt.getBathroomCount() + " phòng" : null);
            addFeature(rows, "Phòng khách",
                    apt.getLivingRoomCount() != null ? apt.getLivingRoomCount() + " phòng" : null);
            addFeature(rows, "Phòng bếp", apt.getKitchenCount() != null ? apt.getKitchenCount() + " phòng" : null);
            addFeature(rows, "Hướng cửa chính", apt.getMainDoorDirection());
            addFeature(rows, "Hướng ban công", apt.getBalconyDirection());
            addFeature(rows, "Tầm nhìn / View", apt.getViewDescription());
            addFeature(rows, "Tình trạng nội thất", apt.getFurnishingStatus());
            addFeature(rows, "Tình trạng pháp lý", apt.getLegalStatus());
        } else if (snapshot.getHouseDetail() != null) {
            var h = snapshot.getHouseDetail();
            addFeature(rows, "Diện tích đất", h.getLandAreaM2() != null ? h.getLandAreaM2() + " m²" : null);
            addFeature(rows, "Mặt tiền", h.getFrontageWidthM() != null ? h.getFrontageWidthM() + " m" : null);
            addFeature(rows, "Chiều dài", h.getLengthM() != null ? h.getLengthM() + " m" : null);
            addFeature(rows, "Độ rộng đường vào",
                    h.getAccessRoadWidthM() != null ? h.getAccessRoadWidthM() + " m" : null);
            addFeature(rows, "Số mặt tiền", h.getFrontageCount() != null ? h.getFrontageCount() + " mặt tiền" : null);
            addFeature(rows, "Tổng số tầng", h.getTotalFloors() != null ? h.getTotalFloors() + " tầng" : null);
            addFeature(rows, "Số người tối đa", h.getMaxOccupants() != null ? h.getMaxOccupants() + " người" : null);
            addFeature(rows, "Số xe tối đa", h.getMaxVehicles() != null ? h.getMaxVehicles() + " xe" : null);
            addFeature(rows, "Số phòng ngủ", h.getBedroomCount() != null ? h.getBedroomCount() + " phòng" : null);
            addFeature(rows, "Số phòng tắm / WC",
                    h.getBathroomCount() != null ? h.getBathroomCount() + " phòng" : null);
            addFeature(rows, "Phòng khách", h.getLivingRoomCount() != null ? h.getLivingRoomCount() + " phòng" : null);
            addFeature(rows, "Phòng bếp", h.getKitchenCount() != null ? h.getKitchenCount() + " phòng" : null);
            addFeature(rows, "Sân thượng", Boolean.TRUE.equals(h.getHasRooftop()) ? "Có" : "Không");
            addFeature(rows, "Garage để xe", Boolean.TRUE.equals(h.getHasGarage()) ? "Có" : "Không");
            addFeature(rows, "Loại lối đi", h.getAccessType());
            addFeature(rows, "Phạm vi cho thuê", h.getRentalScopeDescription());
            addFeature(rows, "Khoảng tầng cho thuê",
                    h.getRentedFloorFrom() != null
                            ? "Từ tầng " + h.getRentedFloorFrom() + " đến tầng " + h.getRentedFloorTo()
                            : null);
            addFeature(rows, "Tình trạng nội thất", h.getFurnishingStatus());
            addFeature(rows, "Tình trạng pháp lý", h.getLegalStatus());
        } else if (snapshot.getRoomDetail() != null) {
            var r = snapshot.getRoomDetail();
            addFeature(rows, "Mã / Số phòng", r.getRoomCode());
            addFeature(rows, "Tầng số", r.getFloorNumber() != null ? "Tầng " + r.getFloorNumber() : null);
            addFeature(rows, "Số người tối đa", r.getMaxOccupants() != null ? r.getMaxOccupants() + " người" : null);
            addFeature(rows, "Số xe tối đa", r.getMaxVehicles() != null ? r.getMaxVehicles() + " xe" : null);
            addFeature(rows, "Nhà vệ sinh", r.getRestroomType());
            addFeature(rows, "Khu vực bếp", r.getKitchenType());
            addFeature(rows, "Cửa sổ", Boolean.TRUE.equals(r.getHasWindow()) ? "Có" : "Không");
            addFeature(rows, "Ban công", r.getBalconyType());
            addFeature(rows, "Gác lửng", Boolean.TRUE.equals(r.getHasMezzanine()) ? "Có" : "Không");
            addFeature(rows, "Lối đi", r.getAccessType());
            addFeature(rows, "Giờ giấc sinh hoạt", r.getAccessHoursType());
            addFeature(rows, "Đồng hồ điện", r.getElectricMeterType());
            addFeature(rows, "Đồng hồ nước", r.getWaterMeterType());
            addFeature(rows, "Chính sách gửi xe", r.getParkingPolicy());
            addFeature(rows, "Tình trạng nội thất", r.getFurnishingStatus());
        } else if (liveListing != null) {
            if (liveListing.getApartmentDetail() != null) {
                var apt = liveListing.getApartmentDetail();
                addFeature(rows, "Tên dự án", apt.getProjectName());
                addFeature(rows, "Tòa / Block", apt.getBuildingBlock());
                addFeature(rows, "Mã căn hộ", apt.getUnitCode());
                addFeature(rows, "Tầng số", apt.getFloorNumber() != null ? "Tầng " + apt.getFloorNumber() : null);
                addFeature(rows, "Số người tối đa", apt.getMaxOccupants() != null ? apt.getMaxOccupants() + " người" : null);
                addFeature(rows, "Số phòng ngủ",
                        apt.getBedroomCount() != null ? apt.getBedroomCount() + " phòng" : null);
                addFeature(rows, "Số phòng tắm / WC",
                        apt.getBathroomCount() != null ? apt.getBathroomCount() + " phòng" : null);
            } else if (liveListing.getRoomDetail() != null) {
                var r = liveListing.getRoomDetail();
                addFeature(rows, "Mã / Số phòng", r.getRoomCode());
                addFeature(rows, "Tầng số", r.getFloorNumber() != null ? "Tầng " + r.getFloorNumber() : null);
                addFeature(rows, "Số người tối đa", r.getMaxOccupants() != null ? r.getMaxOccupants() + " người" : null);
                addFeature(rows, "Số xe tối đa", r.getMaxVehicles() != null ? r.getMaxVehicles() + " xe" : null);
                addFeature(rows, "Gác lửng", Boolean.TRUE.equals(r.getHasMezzanine()) ? "Có" : "Không");
            } else if (liveListing.getHouseDetail() != null) {
                var h = liveListing.getHouseDetail();
                addFeature(rows, "Tổng số tầng", h.getTotalFloors() != null ? h.getTotalFloors() + " tầng" : null);
                addFeature(rows, "Số người tối đa", h.getMaxOccupants() != null ? h.getMaxOccupants() + " người" : null);
                addFeature(rows, "Số xe tối đa", h.getMaxVehicles() != null ? h.getMaxVehicles() + " xe" : null);
                addFeature(rows, "Số phòng ngủ", h.getBedroomCount() != null ? h.getBedroomCount() + " phòng" : null);
            }
        }

        return rows;
    }

    private void addFeature(List<Map<String, String>> rows, String name, Object val) {
        if (val != null && !String.valueOf(val).isBlank() && !"null".equalsIgnoreCase(String.valueOf(val))) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("featureName", name);
            row.put("featureValue", String.valueOf(val).trim());
            rows.add(row);
        }
    }

    private static String resolveRentalScopeDescription(Listing listing) {
        if (listing.getHouseDetail() != null && isNotBlank(listing.getHouseDetail().getRentalScopeDescription())) {
            return listing.getHouseDetail().getRentalScopeDescription();
        }
        if (listing.getCategory() == ListingCategory.ROOM) {
            return "Thuê phòng trọ khép kín / dùng chung";
        }
        if (listing.getCategory() == ListingCategory.APARTMENT) {
            return "Thuê toàn bộ căn hộ chung cư";
        }
        if (listing.getCategory() == ListingCategory.HOUSE) {
            return "Thuê toàn bộ nhà nguyên căn";
        }
        return "Theo phạm vi được bàn giao";
    }

    private static Integer resolveMaxOccupants(Listing listing) {
        if (listing == null) return null;
        if (listing.getApartmentDetail() != null && listing.getApartmentDetail().getMaxOccupants() != null) {
            return listing.getApartmentDetail().getMaxOccupants();
        }
        if (listing.getRoomDetail() != null && listing.getRoomDetail().getMaxOccupants() != null) {
            return listing.getRoomDetail().getMaxOccupants();
        }
        if (listing.getHouseDetail() != null && listing.getHouseDetail().getMaxOccupants() != null) {
            return listing.getHouseDetail().getMaxOccupants();
        }
        return null;
    }

    private static Integer resolveMaxOccupants(ListingSnapshotDto snapshot, Listing liveListing) {
        if (snapshot != null) {
            if (snapshot.getMaxOccupants() != null) {
                return snapshot.getMaxOccupants();
            }
            if (snapshot.getApartmentDetail() != null && snapshot.getApartmentDetail().getMaxOccupants() != null) {
                return snapshot.getApartmentDetail().getMaxOccupants();
            }
            if (snapshot.getRoomDetail() != null && snapshot.getRoomDetail().getMaxOccupants() != null) {
                return snapshot.getRoomDetail().getMaxOccupants();
            }
            if (snapshot.getHouseDetail() != null && snapshot.getHouseDetail().getMaxOccupants() != null) {
                return snapshot.getHouseDetail().getMaxOccupants();
            }
        }
        if (liveListing != null) {
            return resolveMaxOccupants(liveListing);
        }
        return null;
    }

    private static Integer resolveMaxVehicles(Listing listing) {
        if (listing == null) return null;
        if (listing.getRoomDetail() != null && listing.getRoomDetail().getMaxVehicles() != null) {
            return listing.getRoomDetail().getMaxVehicles();
        }
        if (listing.getHouseDetail() != null && listing.getHouseDetail().getMaxVehicles() != null) {
            return listing.getHouseDetail().getMaxVehicles();
        }
        if (listing.getMaxMotorbikeCount() != null || listing.getMaxCarCount() != null) {
            int total = (listing.getMaxMotorbikeCount() != null ? listing.getMaxMotorbikeCount() : 0)
                      + (listing.getMaxCarCount() != null ? listing.getMaxCarCount() : 0);
            return total > 0 ? total : (listing.getMaxMotorbikeCount() != null ? listing.getMaxMotorbikeCount() : null);
        }
        return null;
    }

    private static Integer resolveMaxVehicles(ListingSnapshotDto snapshot, Listing liveListing) {
        if (snapshot != null) {
            if (snapshot.getMaxVehicles() != null) {
                return snapshot.getMaxVehicles();
            }
            if (snapshot.getRoomDetail() != null && snapshot.getRoomDetail().getMaxVehicles() != null) {
                return snapshot.getRoomDetail().getMaxVehicles();
            }
            if (snapshot.getHouseDetail() != null && snapshot.getHouseDetail().getMaxVehicles() != null) {
                return snapshot.getHouseDetail().getMaxVehicles();
            }
            if (snapshot.getMaxMotorbikeCount() != null || snapshot.getMaxCarCount() != null) {
                int total = (snapshot.getMaxMotorbikeCount() != null ? snapshot.getMaxMotorbikeCount() : 0)
                          + (snapshot.getMaxCarCount() != null ? snapshot.getMaxCarCount() : 0);
                if (total > 0) return total;
            }
        }
        if (liveListing != null) {
            return resolveMaxVehicles(liveListing);
        }
        return null;
    }

    private static String resolveUnitNumber(Listing listing) {
        if (listing.getApartmentDetail() != null && isNotBlank(listing.getApartmentDetail().getUnitCode())) {
            return "Căn hộ " + listing.getApartmentDetail().getUnitCode().trim();
        }
        if (listing.getRoomDetail() != null && isNotBlank(listing.getRoomDetail().getRoomCode())) {
            return "Phòng " + listing.getRoomDetail().getRoomCode().trim();
        }
        return "";
    }

    private static String resolveFloor(Listing listing) {
        Integer floor = null;
        if (listing.getApartmentDetail() != null) {
            floor = listing.getApartmentDetail().getFloorNumber();
        } else if (listing.getRoomDetail() != null) {
            floor = listing.getRoomDetail().getFloorNumber();
        } else if (listing.getHouseDetail() != null && listing.getHouseDetail().getTotalFloors() != null) {
            return "Nhà " + listing.getHouseDetail().getTotalFloors() + " tầng";
        }
        return floor != null ? "Tầng " + floor : "";
    }

    private static String resolveBuildingName(Listing listing) {
        if (listing.getApartmentDetail() != null) {
            return joinNonBlank(" ",
                    nullToEmpty(listing.getApartmentDetail().getProjectName()),
                    nullToEmpty(listing.getApartmentDetail().getBuildingBlock()));
        }
        return "";
    }

    // --- Thời hạn thuê ---

    private Map<String, Object> buildLease(RentalRequest request, ListingSnapshotDto snapshot, Listing liveListing) {
        Map<String, Object> map = new LinkedHashMap<>();
        LocalDate start = request.getMoveInDate() != null ? request.getMoveInDate() : LocalDate.now();
        int months = request.getLeaseMonths() != null ? request.getLeaseMonths() : 12;
        LocalDate end = start.plusMonths(months).minusDays(1);

        map.put("startDateText", start.format(DATE_FORMATTER));
        map.put("endDateText", end.format(DATE_FORMATTER));
        map.put("durationMonths", months);
        map.put("durationText", formatDurationText(months));
        map.put("handoverDateText", start.format(DATE_FORMATTER));
        return map;
    }

    // --- Giá thuê & cọc ---

    private Map<String, Object> buildFinancial(RentalRequest request, ListingSnapshotDto snapshot, Listing liveListing,
            PaymentRequest payment) {
        Map<String, Object> map = new LinkedHashMap<>();

        // Ưu tiên giá đã chốt: effectiveMonthlyRent từ request hoặc snapshot
        BigDecimal rent;
        if (request != null && request.getEffectiveMonthlyRent() != null) {
            rent = request.getEffectiveMonthlyRent();
        } else if (request != null && request.getMonthlyRentPrice() != null) {
            rent = request.getMonthlyRentPrice();
        } else if (snapshot != null && snapshot.getPriceAmount() != null) {
            rent = snapshot.getPriceAmount();
        } else {
            rent = liveListing != null && liveListing.getPriceAmount() != null ? liveListing.getPriceAmount()
                    : BigDecimal.ZERO;
        }

        BigDecimal deposit;
        if (request != null && request.getDepositAmount() != null) {
            deposit = request.getDepositAmount();
        } else if (snapshot != null && snapshot.getDepositAmount() != null) {
            deposit = snapshot.getDepositAmount();
        } else {
            deposit = rent;
        }

        String paymentCycle = (snapshot != null && snapshot.getPaymentCycle() != null) ? paymentCycleLabelStr(snapshot.getPaymentCycle())
                : (liveListing != null ? paymentCycleLabel(liveListing.getPaymentCycle()) : "Hàng tháng");

        map.put("amountValue", rent.toPlainString());
        map.put("amountNumber", ContractRenderService.formatVND(rent) + "/tháng");
        map.put("amountWords", VietnameseCurrencyTextConverter.toWords(rent));
        map.put("paymentCycle", paymentCycle);
        map.put("paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng");
        map.put("paymentMethod", "Chuyển khoản trực tiếp vào tài khoản ngân hàng của Bên A chỉ định trong Hợp đồng này");
        map.put("depositAmountValue", deposit.toPlainString());
        map.put("depositAmountNumber", ContractRenderService.formatVND(deposit));
        map.put("depositAmountWords", VietnameseCurrencyTextConverter.toWords(deposit));
        map.put("depositDescription", "Tiền đặt cọc được Bên A hoàn trả cho Bên B sau khi hết hạn hợp đồng, "
                + "sau khi đã khấu trừ các khoản chi phí phát sinh chưa thanh toán (nếu có).");
        return map;
    }

    // --- Bảng phí dịch vụ ---

    private List<Map<String, Object>> buildCharges(RentalRequest request, ListingSnapshotDto snapshot,
            Listing liveListing, int occupants) {
        if (request != null && isNotBlank(request.getCostBreakdownSnapshot())) {
            try {
                List<PredictableChargeItem> predictable = objectMapper.readValue(
                        request.getCostBreakdownSnapshot(),
                        new TypeReference<List<PredictableChargeItem>>() {
                        });
                List<ExcludedChargeItem> excluded = Collections.emptyList();
                if (isNotBlank(request.getExcludedChargesSnapshot())) {
                    excluded = objectMapper.readValue(
                            request.getExcludedChargesSnapshot(),
                            new TypeReference<List<ExcludedChargeItem>>() {
                            });
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                for (PredictableChargeItem item : predictable) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", item.displayName());
                    String amountAndMethod;
                    if (item.includedInRent()) {
                        amountAndMethod = "Đã bao gồm trong giá thuê";
                    } else if ("FREE".equals(item.billingMethod())) {
                        amountAndMethod = "Miễn phí";
                    } else if ("PER_PERSON_MONTH".equals(item.billingMethod())) {
                        amountAndMethod = ContractRenderService.formatVND(item.unitAmount()) + " / người / tháng × "
                                + occupants + " người = " + ContractRenderService.formatVND(item.amount()) + " / tháng";
                    } else if ("PER_VEHICLE_MONTH".equals(item.billingMethod())) {
                        amountAndMethod = ContractRenderService.formatVND(item.unitAmount()) + " / xe / tháng × "
                                + item.quantity() + " xe = " + ContractRenderService.formatVND(item.amount())
                                + " / tháng";
                    } else if ("PER_MONTH".equals(item.billingMethod())) {
                        amountAndMethod = ContractRenderService.formatVND(item.amount()) + " / tháng";
                    } else {
                        amountAndMethod = firstNonBlank(item.note(),
                                ContractRenderService.formatVND(item.amount()) + " / tháng");
                    }
                    row.put("amountAndMethod", amountAndMethod);
                    row.put("note", firstNonBlank(item.note(), "-"));
                    row.put("estimatedMonthlyAmount", item.amount() != null ? item.amount().toPlainString() : null);
                    row.put("chargeType", item.chargeType());
                    row.put("billingMethod", item.billingMethod());
                    rows.add(row);
                }

                for (ExcludedChargeItem item : excluded) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", item.displayName());
                    row.put("amountAndMethod", firstNonBlank(item.reason(), "Chưa bao gồm trong giá thuê"));
                    row.put("note", "Chưa bao gồm");
                    row.put("estimatedMonthlyAmount", null);
                    row.put("chargeType", item.chargeType());
                    row.put("billingMethod", item.billingMethod());
                    rows.add(row);
                }
                return rows;
            } catch (Exception e) {
                log.warn("Failed to parse charge snapshots from RentalRequest, falling back to listing charges", e);
            }
        }

        if (snapshot != null && snapshot.getCharges() != null && !snapshot.getCharges().isEmpty()) {
            return buildChargesFromSnapshot(request, snapshot, occupants);
        }

        return buildChargesFromListing(request, liveListing, occupants);
    }

    private List<Map<String, Object>> buildChargesFromSnapshot(RentalRequest request, ListingSnapshotDto snapshot,
            int occupants) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int motorbikes = request != null && request.getMotorbikeCount() != null ? request.getMotorbikeCount() : 0;
        int cars = request != null && request.getCarCount() != null ? request.getCarCount() : 0;

        for (ListingSnapshotDto.ChargeItemSnapshot charge : snapshot.getCharges()) {
            Map<String, Object> row = new LinkedHashMap<>();
            String name = firstNonBlank(charge.getCustomName(), chargeTypeLabelStr(charge.getChargeType()));
            row.put("name", name);
            row.put("amountAndMethod",
                    chargeAmountAndMethodStr(charge, occupants, motorbikes, cars, snapshot.getAreaM2()));
            row.put("note", firstNonBlank(charge.getDescription(), "-"));

            BigDecimal estimated = estimateMonthlyAmountStr(charge, occupants, motorbikes, cars, snapshot.getAreaM2());
            row.put("estimatedMonthlyAmount", estimated == null ? null : estimated.toPlainString());
            row.put("chargeType", charge.getChargeType());
            row.put("billingMethod", charge.getBillingMethod());
            row.put("billingMethodText", billingMethodUnitLabel(charge.getBillingMethod()));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildChargesFromListing(RentalRequest request, Listing listing, int occupants) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (listing == null || listing.getCharges() == null || listing.getCharges().isEmpty()) {
            return rows;
        }

        int motorbikes = request != null && request.getMotorbikeCount() != null ? request.getMotorbikeCount() : 0;
        int cars = request != null && request.getCarCount() != null ? request.getCarCount() : 0;

        List<ListingCharge> sorted = new ArrayList<>(listing.getCharges());
        sorted.sort(Comparator.comparing(c -> c.getSortOrder() == null ? Integer.MAX_VALUE : c.getSortOrder()));

        for (ListingCharge charge : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", chargeName(charge));
            row.put("amountAndMethod", chargeAmountAndMethod(charge, occupants, motorbikes, cars, listing.getAreaM2()));
            row.put("note", firstNonBlank(charge.getDescription(), "-"));

            BigDecimal estimated = estimateMonthlyAmount(charge, occupants, motorbikes, cars, listing.getAreaM2());
            row.put("estimatedMonthlyAmount", estimated == null ? null : estimated.toPlainString());
            String method = charge.getBillingMethod() == null ? null : charge.getBillingMethod().name();
            row.put("chargeType", charge.getChargeType() == null ? null : charge.getChargeType().name());
            row.put("billingMethod", method);
            row.put("billingMethodText", billingMethodUnitLabel(method));
            rows.add(row);
        }
        return rows;
    }

    // --- Trang thiết bị bàn giao ---

    private List<Map<String, Object>> buildEquipments(ListingSnapshotDto snapshot, Listing listing) {
        List<Map<String, Object>> rows = new ArrayList<>();

        if (snapshot != null && snapshot.getFurnishings() != null && !snapshot.getFurnishings().isEmpty()) {
            int index = 1;
            for (ListingSnapshotDto.FurnishingItemSnapshot asset : snapshot.getFurnishings()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", index++);
                row.put("name", nullToEmpty(asset.getAssetName()));
                row.put("quantity", asset.getQuantity() != null ? String.valueOf(asset.getQuantity()) : "1");
                row.put("condition", handoverConditionLabel(asset.getHandoverCondition(), asset.getConditionNote()));
                row.put("status", handoverConditionLabel(asset.getHandoverCondition(), null));
                rows.add(row);
            }
            return rows;
        }

        if (listing == null || listing.getFurnishings() == null || listing.getFurnishings().isEmpty()) {
            return rows;
        }

        List<ListingFurnishingAsset> sorted = new ArrayList<>(listing.getFurnishings());
        sorted.sort(Comparator.comparing(a -> a.getSortOrder() == null ? Integer.MAX_VALUE : a.getSortOrder()));

        int index = 1;
        for (ListingFurnishingAsset asset : sorted) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("name", nullToEmpty(asset.getAssetName()));
            row.put("quantity", asset.getQuantity() != null ? String.valueOf(asset.getQuantity()) : "1");
            row.put("condition", conditionText(asset));
            row.put("status",
                    asset.getHandoverCondition() != null ? asset.getHandoverCondition().label() : "Bình thường");
            rows.add(row);
        }
        return rows;
    }

    // --- Tiện ích & quyền sử dụng ({{#amenitiesTable}}) ---

    private List<Map<String, Object>> buildAmenities(
            ListingSnapshotDto snapshot,
            Listing liveListing,
            RentalRequest request,
            List<Map<String, Object>> equipments) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        // Thu thập tên các tài sản đã có trong equipmentTable để loại trừ (tránh trùng
        // lặp tài sản với tiện ích)
        Set<String> equipmentNames = new HashSet<>();
        if (equipments != null) {
            for (Map<String, Object> eq : equipments) {
                String eqName = String.valueOf(eq.get("name")).trim().toLowerCase();
                if (!eqName.isBlank()) {
                    equipmentNames.add(eqName);
                }
            }
        }

        int index = 1;

        // 1. Tiện ích từ snapshot hoặc live listing
        if (snapshot != null && snapshot.getAmenities() != null) {
            for (ListingSnapshotDto.AmenityItemSnapshot item : snapshot.getAmenities()) {
                if (item == null || item.getName() == null)
                    continue;
                String normalized = item.getName().trim().toLowerCase();
                if (isEquipmentDuplicate(normalized, equipmentNames)) {
                    continue;
                }
                if (seenNames.add(normalized)) {
                    rows.add(createAmenityRow(index++, item.getCode(), item.getName(),
                            item.getScope(), "Đã bao gồm trong giá thuê",
                            amenityConditionOf(item.getCode()), item.getSourceType()));
                }
            }
        } else if (liveListing != null && liveListing.getAmenities() != null) {
            for (Amenity a : liveListing.getAmenities()) {
                if (a == null || a.getName() == null)
                    continue;
                String normalized = a.getName().trim().toLowerCase();
                if (isEquipmentDuplicate(normalized, equipmentNames)) {
                    continue;
                }
                if (seenNames.add(normalized)) {
                    rows.add(createAmenityRow(index++, a.getCode(), a.getName(),
                            "Riêng trong căn/phòng/nhà", "Đã bao gồm trong giá thuê",
                            amenityConditionOf(a.getCode()), "LISTING"));
                }
            }
        }

        // 2. Custom amenities
        if (snapshot != null && snapshot.getCustomAmenities() != null) {
            for (String ca : snapshot.getCustomAmenities()) {
                if (ca == null || ca.isBlank())
                    continue;
                String normalized = ca.trim().toLowerCase();
                if (isEquipmentDuplicate(normalized, equipmentNames))
                    continue;
                if (seenNames.add(normalized)) {
                    rows.add(createAmenityRow(index++, "CUSTOM", ca.trim(),
                            "Riêng trong căn/phòng/nhà", "Đã bao gồm trong giá thuê",
                            "Theo thỏa thuận", "CUSTOM"));
                }
            }
        } else if (liveListing != null && liveListing.getCustomAmenities() != null) {
            for (ListingCustomAmenity ca : liveListing.getCustomAmenities()) {
                if (ca == null || ca.getName() == null || ca.getName().isBlank())
                    continue;
                String normalized = ca.getName().trim().toLowerCase();
                if (isEquipmentDuplicate(normalized, equipmentNames))
                    continue;
                if (seenNames.add(normalized)) {
                    rows.add(createAmenityRow(index++, "CUSTOM", ca.getName().trim(),
                            "Riêng trong căn/phòng/nhà", "Đã bao gồm trong giá thuê",
                            "Theo thỏa thuận", "CUSTOM"));
                }
            }
        }

        // 3. Quy tắc PETS_ALLOWED
        boolean hasPetAmenity = rows.stream()
                .anyMatch(r -> "PETS_ALLOWED".equalsIgnoreCase(String.valueOf(r.get("code"))));
        if (hasPetAmenity) {
            // Chuẩn hóa row PETS_ALLOWED
            for (Map<String, Object> r : rows) {
                if ("PETS_ALLOWED".equalsIgnoreCase(String.valueOf(r.get("code")))) {
                    r.put("name", "Được phép nuôi thú cưng");
                    r.put("scope", "Riêng trong căn/phòng/nhà");
                    r.put("costText", "Miễn phí");
                    r.put("conditionText",
                            "Tuân thủ nội quy chung, Bên B chịu trách nhiệm giữ gìn vệ sinh, hạn chế tiếng ồn và bồi thường thiệt hại phát sinh (nếu có)");
                }
            }
        }

        // 4. Quyền gửi xe (PARKING)
        int motorbikes = request != null && request.getMotorbikeCount() != null ? request.getMotorbikeCount() : 0;
        int cars = request != null && request.getCarCount() != null ? request.getCarCount() : 0;
        boolean hasParkingAmenity = rows.stream()
                .anyMatch(r -> "PARKING".equalsIgnoreCase(String.valueOf(r.get("code")))
                        || String.valueOf(r.get("name")).toLowerCase().contains("xe"));

        if (!hasParkingAmenity && (motorbikes > 0 || cars > 0)) {
            String condition = "Đăng ký " + motorbikes + " xe máy" + (cars > 0 ? ", " + cars + " ô tô" : "");
            rows.add(createAmenityRow(index++, "PARKING", "Chỗ để xe", "Dùng chung",
                    "Theo biểu phí dịch vụ gửi xe", condition, "DERIVED_POLICY"));
        }

        return rows;
    }

    private static boolean isEquipmentDuplicate(String amenityName, Set<String> equipmentNames) {
        for (String eq : equipmentNames) {
            if (eq.contains(amenityName) || amenityName.contains(eq)) {
                // Nếu tên tiện ích chứa từ khóa thiết bị nội thất bàn giao cơ bản
                if (amenityName.contains("máy lạnh") || amenityName.contains("điều hòa")
                        || amenityName.contains("tủ lạnh") || amenityName.contains("máy giặt")
                        || amenityName.contains("máy nước nóng") || amenityName.contains("giường")
                        || amenityName.contains("bàn") || amenityName.contains("tủ quần áo")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> createAmenityRow(
            int index, String code, String name, String scope, String costText, String conditionText,
            String sourceType) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("code", code != null ? code : "");
        row.put("name", name != null ? name : "");
        row.put("scope", scope != null ? scope : "Riêng trong căn/phòng/nhà");
        row.put("costText", costText != null ? costText : "Đã bao gồm trong giá thuê");
        row.put("conditionText", conditionText != null ? conditionText : "Theo quy chế vận hành và nội quy chung");
        row.put("sourceType", sourceType != null ? sourceType : "LISTING");
        return row;
    }

    private static String amenityConditionOf(String code) {
        if ("PETS_ALLOWED".equalsIgnoreCase(code)) {
            return "Tuân thủ nội quy chung, Bên B chịu trách nhiệm giữ gìn vệ sinh, hạn chế tiếng ồn và bồi thường thiệt hại phát sinh (nếu có)";
        }
        if ("PARKING".equalsIgnoreCase(code)) {
            return "Theo nội quy bãi đỗ xe và biểu phí dịch vụ của đơn vị quản lý";
        }
        if ("ELEVATOR".equalsIgnoreCase(code)) {
            return "Theo nội quy sử dụng thang máy và quy chuẩn an toàn chung";
        }
        if ("SWIMMING_POOL".equalsIgnoreCase(code) || "GYM".equalsIgnoreCase(code)) {
            return "Theo khung giờ hoạt động và quy chế sử dụng của ban quản lý";
        }
        return "Theo nội quy, khung giờ và tình trạng vận hành của đơn vị quản lý";
    }

    // --- Bảng phụ lục thanh toán ban đầu ({{#initialPaymentTable}}) ---

    private Map<String, Object> buildInitialPayment(
            RentalRequest request,
            PaymentRequest payment,
            Map<String, Object> financial,
            List<Map<String, Object>> charges) {
        Map<String, Object> map = new LinkedHashMap<>();

        String status = "Khoản chuyển đã được hai bên xác nhận";
        String paidAt = "";
        String payerReportedAt = "";
        String payeeConfirmedAt = "";
        String confirmedAt = "";
        String transferRef = "";
        String bankTxnRef = "";

        BigDecimal monthlyRent = (request != null && request.getEffectiveMonthlyRent() != null) ? request.getEffectiveMonthlyRent()
                : ((request != null && request.getMonthlyRentPrice() != null) ? request.getMonthlyRentPrice() : BigDecimal.ZERO);
        BigDecimal deposit = (request != null && request.getDepositAmount() != null) ? request.getDepositAmount() : monthlyRent;
        BigDecimal monthlyCharges = (request != null && request.getEstimatedMonthlyCharges() != null) ? request.getEstimatedMonthlyCharges()
                : BigDecimal.ZERO;
        BigDecimal total = (request != null && request.getEstimatedInitialTotal() != null) ? request.getEstimatedInitialTotal()
                : monthlyRent.add(deposit).add(monthlyCharges);

        if (payment != null) {
            if (payment.getStatus() != null) {
                status = switch (payment.getStatus()) {
                    case CONFIRMED -> "Khoản chuyển đã được hai bên xác nhận";
                    case TRANSFER_REPORTED -> "Người thuê đã báo chuyển, đang chờ chủ nhà xác nhận";
                    case AWAITING_TRANSFER -> "Chờ người thuê chuyển khoản";
                    case REJECTED -> "Chủ nhà từ chối xác nhận";
                    case DISPUTED -> "Đang chờ đối soát tranh chấp";
                    case EXPIRED -> "Yêu cầu thanh toán đã hết hạn";
                    case CANCELLED -> "Yêu cầu thanh toán đã hủy";
                    default -> payment.getStatus().name();
                };
            }
            if (payment.getConfirmedAt() != null) {
                confirmedAt = DATE_TIME_FORMATTER.format(payment.getConfirmedAt().atZone(VIETNAM_ZONE));
                paidAt = confirmedAt;
            }
            if (payment.getPayerReportedAt() != null) {
                payerReportedAt = DATE_TIME_FORMATTER.format(payment.getPayerReportedAt().atZone(VIETNAM_ZONE));
            }
            if (payment.getPayeeConfirmedAt() != null) {
                payeeConfirmedAt = DATE_TIME_FORMATTER.format(payment.getPayeeConfirmedAt().atZone(VIETNAM_ZONE));
            }
            if (payment.getTransferReference() != null) {
                transferRef = payment.getTransferReference();
            }
            if (payment.getBankTransactionReference() != null) {
                bankTxnRef = payment.getBankTransactionReference();
            }
            if (payment.getTotalAmount() != null) {
                total = payment.getTotalAmount();
            }
        }

        map.put("status", status);
        map.put("paidAt", paidAt);
        map.put("payerReportedAt", payerReportedAt);
        map.put("payeeConfirmedAt", payeeConfirmedAt);
        map.put("confirmedAt", confirmedAt);
        map.put("transferReference", transferRef);
        map.put("bankTransactionReference", bankTxnRef);
        map.put("transactionCode", !transferRef.isBlank() ? transferRef : (!bankTxnRef.isBlank() ? bankTxnRef : "HS-DIRECT"));
        map.put("monthlyRent", ContractRenderService.formatVND(monthlyRent));
        map.put("monthlyCharges", ContractRenderService.formatVND(monthlyCharges));
        map.put("depositAmount", ContractRenderService.formatVND(deposit));
        map.put("totalAmount", ContractRenderService.formatVND(total));
        map.put("currency", "VND");

        // Các hàng của bảng {{#initialPaymentTable}}
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(createPaymentRow("Tiền thuê kỳ đầu", ContractRenderService.formatVND(monthlyRent),
                "Chuyển khoản trực tiếp vào tài khoản Bên A"));
        if (monthlyCharges.compareTo(BigDecimal.ZERO) > 0) {
            rows.add(createPaymentRow("Chi phí cố định kỳ đầu", ContractRenderService.formatVND(monthlyCharges),
                    "Khoản phí dịch vụ cố định tháng đầu"));
        }
        rows.add(createPaymentRow("Tiền đặt cọc", ContractRenderService.formatVND(deposit),
                "Bên A hoàn cọc trực tiếp cho Bên B khi chấm dứt hợp đồng"));

        String noteTotal = "Khoản chuyển đã được hai bên xác nhận trực tiếp";
        if (isNotBlank(payerReportedAt) && isNotBlank(payeeConfirmedAt)) {
            noteTotal = "Bên B báo chuyển: " + payerReportedAt + " | Bên A xác nhận: " + payeeConfirmedAt;
        } else if (isNotBlank(confirmedAt)) {
            noteTotal = "Hai bên xác nhận lúc: " + confirmedAt;
        }
        rows.add(createPaymentRow("Tổng cộng", ContractRenderService.formatVND(total), noteTotal));

        map.put("rows", rows);
        return map;
    }

    private Map<String, String> createPaymentRow(String name, String amount, String note) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("itemName", name);
        row.put("amountText", amount);
        row.put("note", note);
        return row;
    }

    // --- Chính sách & điều khoản hợp đồng ---

    private Map<String, Object> buildPolicies(ListingSnapshotDto snapshot, RentalRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("paymentDueDay", "Từ ngày 01 đến ngày 05 hàng tháng");
        map.put("paymentCycle",
                snapshot.getPaymentCycle() != null ? paymentCycleLabelStr(snapshot.getPaymentCycle()) : "Hàng tháng");
        map.put("gracePeriodDays", 5);
        map.put("latePaymentPolicy",
                "Quá 05 ngày kể từ ngày đến hạn thanh toán mà chưa hoàn tất, Bên A có quyền tính lãi suất chậm trả hoặc tạm ngừng cung cấp dịch vụ sau khi thông báo trước 03 ngày");
        map.put("noticeDaysBeforeMoveOut", 30);
        map.put("noticeDaysBeforeTermination", 30);
        map.put("depositRefundDays", 15);
        map.put("depositDeductionConditions",
                "Khấu trừ chi phí dịch vụ chưa thanh toán hoặc bồi thường thiệt hại tài sản theo biên bản bàn giao");
        map.put("petPolicy",
                "Tuân thủ nội quy khu vực; đảm bảo vệ sinh, tiếng ồn và bồi thường thiệt hại phát sinh (nếu có)");
        map.put("subleasePolicy",
                "Không được tự ý cho thuê lại hoặc chuyển nhượng quyền thuê cho bên thứ ba khi chưa có chấp thuận bằng văn bản của Bên A");
        map.put("overnightGuestPolicy",
                "Khách ở qua đêm phải đăng ký lưu trú theo đúng quy định của pháp luật về cư trú");
        map.put("smokingPolicy", "Không hút thuốc tại khu vực chung và các khu vực có nguy cơ cháy nổ cao");
        map.put("propertyInspectionNotice",
                "Bên A có quyền kiểm tra tài sản định kỳ sau khi thông báo trước cho Bên B ít nhất 24 giờ, trừ tình huống khẩn cấp");
        map.put("vatPolicy",
                Boolean.TRUE.equals(snapshot.getVatIncluded()) ? "Giá thuê đã bao gồm thuế GTGT (nếu áp dụng)"
                        : "Giá thuê chưa bao gồm thuế GTGT");
        map.put("disputeResolution",
                "Hai bên giải quyết tranh chấp thông qua thương lượng hòa giải; trường hợp không đạt thỏa thuận sẽ đưa ra Tòa án có thẩm quyền tại địa phương nơi có bất động sản");
        map.put("forceMajeure", "Thực hiện theo quy định của Bộ luật Dân sự 2015 về sự kiện bất khả kháng");
        return map;
    }

    // --- Chỉ số công tơ bàn giao ---

    private Map<String, Object> buildMeters(ListingSnapshotDto snapshot, Listing listing, int occupants) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("electricityInitial", "");
        map.put("waterInitial", waterMeterNotApplicableText(snapshot, listing, occupants));
        if (snapshot != null && snapshot.getRoomDetail() != null) {
            var r = snapshot.getRoomDetail();
            map.put("electricMeterType", meterTypeLabel(r.getElectricMeterType()));
            map.put("waterMeterType", meterTypeLabel(r.getWaterMeterType()));
        } else if (listing != null && listing.getRoomDetail() != null) {
            var r = listing.getRoomDetail();
            map.put("electricMeterType",
                    meterTypeLabel(r.getElectricMeterType() != null ? r.getElectricMeterType().name() : null));
            map.put("waterMeterType",
                    meterTypeLabel(r.getWaterMeterType() != null ? r.getWaterMeterType().name() : null));
        }
        return map;
    }

    private static String meterTypeLabel(String type) {
        if ("PRIVATE".equalsIgnoreCase(type) || "SUB_METER".equalsIgnoreCase(type)) {
            return "Công tơ / đồng hồ riêng từng phòng";
        }
        if ("SHARED".equalsIgnoreCase(type) || "PER_PERSON".equalsIgnoreCase(type)) {
            return "Dùng chung / chia đều";
        }
        return isNotBlank(type) ? type : "";
    }

    private static String waterMeterNotApplicableText(ListingSnapshotDto snapshot, Listing listing, int occupants) {
        if (snapshot != null && snapshot.getCharges() != null) {
            for (ListingSnapshotDto.ChargeItemSnapshot charge : snapshot.getCharges()) {
                if ("WATER".equalsIgnoreCase(charge.getChargeType())
                        && "PER_PERSON_MONTH".equalsIgnoreCase(charge.getBillingMethod())) {
                    if (charge.getAmount() == null) {
                        return "Không áp dụng - nước tính theo " + occupants + " người";
                    }
                    BigDecimal total = charge.getAmount().multiply(BigDecimal.valueOf(occupants));
                    return "Không áp dụng - nước tính theo " + occupants + " người ("
                            + ContractRenderService.formatVND(total) + "/tháng)";
                }
            }
        }

        if (listing != null && listing.getCharges() != null) {
            return listing.getCharges().stream()
                    .filter(charge -> charge.getChargeType() == ChargeType.WATER)
                    .filter(charge -> charge.getBillingMethod() == BillingMethod.PER_PERSON_MONTH)
                    .findFirst()
                    .map(charge -> {
                        if (charge.getAmount() == null) {
                            return "Không áp dụng - nước tính theo " + occupants + " người";
                        }
                        BigDecimal total = charge.getAmount().multiply(BigDecimal.valueOf(occupants));
                        return "Không áp dụng - nước tính theo " + occupants + " người ("
                                + ContractRenderService.formatVND(total) + "/tháng)";
                    })
                    .orElse("");
        }

        return "";
    }

    private static String conditionText(ListingFurnishingAsset asset) {
        String condStr = asset.getHandoverCondition() != null ? asset.getHandoverCondition().name() : null;
        return handoverConditionLabel(condStr, asset.getConditionNote());
    }

    public static String handoverConditionLabel(String conditionStr, String conditionNote) {
        if (conditionStr == null || conditionStr.isBlank()) {
            return isNotBlank(conditionNote) ? conditionNote.trim() : "Bình thường";
        }
        String label = switch (conditionStr.trim().toUpperCase()) {
            case "BRAND_NEW" -> "Mới 100%";
            case "GOOD" -> "Còn tốt";
            case "NORMAL" -> "Bình thường";
            case "USED_ACCEPTABLE" -> "Cũ, còn dùng được";
            case "MINOR_DAMAGE" -> "Hư hỏng nhẹ";
            default -> conditionStr.trim();
        };
        return isNotBlank(conditionNote) ? label + " — " + conditionNote.trim() : label;
    }

    public static String billingMethodUnitLabel(String methodStr) {
        if (methodStr == null || methodStr.isBlank()) {
            return "Tháng";
        }
        return switch (methodStr.trim().toUpperCase()) {
            case "PER_MONTH" -> "Tháng";
            case "PER_VEHICLE_MONTH" -> "Xe / tháng";
            case "PER_KWH" -> "kWh (theo công tơ)";
            case "PER_M3" -> "m³ (theo đồng hồ)";
            case "PER_PERSON_MONTH" -> "Người / tháng";
            case "PER_M2_MONTH" -> "m² / tháng";
            case "PER_HOUR" -> "Giờ";
            case "FREE" -> "Miễn phí";
            case "INCLUDED" -> "Đã bao gồm";
            case "STATE_WATER_RATE" -> "Theo giá nhà nước";
            case "NOT_APPLICABLE" -> "Không áp dụng";
            case "NEGOTIABLE" -> "Thỏa thuận";
            case "CUSTOM" -> "Tùy chỉnh";
            default -> methodStr.trim();
        };
    }

    private User findUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private String permanentAddressOf(String userId) {
        if (userId == null || userId.isBlank()) {
            return "";
        }
        return addressRepository.findByUser_IdAndActiveTrue(userId)
                .map(Address::getFullAddress)
                .map(ContractDataBuilder::nullToEmpty)
                .orElse("");
    }

    private static String fullNameOf(User user, String fallback) {
        if (user == null) {
            return fallback;
        }
        String name = joinNonBlank(" ", nullToEmpty(user.getFirstName()), nullToEmpty(user.getLastName()));
        return isNotBlank(name) ? name : fallback;
    }

    private static int resolveOccupantCount(RentalRequest request) {
        Integer count = request.getOccupantCount();
        return count != null && count > 0 ? count : 1;
    }

    static String categoryLabel(ListingCategory category) {
        if (category == null)
            return "";
        return switch (category) {
            case HOUSE -> "Nhà nguyên căn";
            case APARTMENT -> "Căn hộ chung cư";
            case ROOM -> "Phòng trọ";
            case OFFICE -> "Văn phòng";
            case COMMERCIAL_SPACE -> "Mặt bằng kinh doanh";
        };
    }

    private static String paymentCycleLabel(PaymentCycle cycle) {
        if (cycle == null)
            return "Hàng tháng";
        return switch (cycle) {
            case MONTHLY -> "Hàng tháng";
            case EVERY_2_MONTHS -> "Định kỳ 2 tháng một lần";
            case QUARTERLY -> "Hàng quý (3 tháng một lần)";
            case EVERY_6_MONTHS -> "Định kỳ 6 tháng một lần";
            case NEGOTIABLE -> "Theo thỏa thuận giữa hai bên";
        };
    }

    private static String paymentCycleLabelStr(String cycle) {
        if (cycle == null)
            return "Hàng tháng";
        try {
            return paymentCycleLabel(PaymentCycle.valueOf(cycle.toUpperCase()));
        } catch (Exception e) {
            return "Hàng tháng";
        }
    }

    static String formatDurationText(int months) {
        if (months < 12)
            return months + " tháng";
        int years = months / 12;
        int rest = months % 12;
        if (rest == 0)
            return years + " năm (" + months + " tháng)";
        return years + " năm " + rest + " tháng (" + months + " tháng)";
    }

    private static String stripTrailingZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String joinNonBlank(String separator, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            if (isNotBlank(part)) {
                kept.add(part.trim());
            }
        }
        return String.join(separator, kept);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String chargeName(ListingCharge charge) {
        if (charge.getChargeType() == ChargeType.OTHER) {
            return firstNonBlank(charge.getCustomName(), "Phí dịch vụ khác");
        }
        return firstNonBlank(charge.getCustomName(), chargeTypeLabel(charge.getChargeType()));
    }

    private static String chargeTypeLabel(ChargeType type) {
        if (type == null)
            return "Phí dịch vụ";
        return switch (type) {
            case ELECTRICITY -> "Tiền điện";
            case WATER -> "Tiền nước";
            case MANAGEMENT -> "Phí quản lý";
            case INTERNET -> "Internet / WiFi";
            case SERVICE_OR_GARBAGE -> "Phí dịch vụ, vệ sinh & rác";
            case MOTORBIKE_PARKING -> "Phí gửi xe máy";
            case CAR_PARKING -> "Phí gửi xe ô tô";
            case OVERTIME_AIR_CONDITIONING -> "Điều hòa ngoài giờ";
            case OTHER -> "Phí dịch vụ khác";
        };
    }

    private static String chargeTypeLabelStr(String typeStr) {
        if (typeStr == null)
            return "Phí dịch vụ";
        try {
            return chargeTypeLabel(ChargeType.valueOf(typeStr.toUpperCase()));
        } catch (Exception e) {
            return "Phí dịch vụ";
        }
    }

    private static String chargeAmountAndMethod(ListingCharge charge, int occupants, int motorbikes, int cars,
            BigDecimal areaM2) {
        if (charge.isIncludedInRent() || charge.getBillingMethod() == BillingMethod.INCLUDED) {
            return "Đã bao gồm trong giá thuê";
        }

        BillingMethod method = charge.getBillingMethod();
        BigDecimal amount = charge.getAmount();
        String price = amount != null ? ContractRenderService.formatVND(amount) : null;

        if (method == null) {
            return price != null ? price : "Theo thỏa thuận";
        }

        return switch (method) {
            case FREE -> "Miễn phí";
            case NOT_APPLICABLE -> "Bên thuê tự đăng ký với đơn vị cung cấp";
            case NEGOTIABLE -> "Theo thỏa thuận giữa hai bên";
            case STATE_WATER_RATE -> "Theo đơn giá nhà nước";
            case PER_KWH -> price == null ? "Theo chỉ số công tơ" : price + " / kWh (theo chỉ số công tơ)";
            case PER_M3 -> price == null ? "Theo chỉ số đồng hồ" : price + " / m³ (theo chỉ số đồng hồ)";
            case PER_HOUR -> price == null ? "Theo giờ sử dụng" : price + " / giờ";
            case PER_VEHICLE_MONTH -> {
                int count = charge.getChargeType() == ChargeType.MOTORBIKE_PARKING ? motorbikes : cars;
                if (price == null)
                    yield "Theo số xe đăng ký";
                BigDecimal total = amount.multiply(BigDecimal.valueOf(count));
                yield price + " / xe / tháng × " + count + " xe = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case PER_MONTH -> price == null ? "Theo tháng" : price + " / tháng";
            case PER_PERSON_MONTH -> {
                if (amount == null)
                    yield "Theo số người ở";
                BigDecimal total = amount.multiply(BigDecimal.valueOf(occupants));
                yield price + " / người / tháng × " + occupants + " người = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case PER_M2_MONTH -> {
                if (amount == null)
                    yield "Theo diện tích";
                if (areaM2 == null)
                    yield price + " / m² / tháng";
                BigDecimal total = amount.multiply(areaM2);
                yield price + " / m² / tháng × " + stripTrailingZeros(areaM2) + " m² = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case CUSTOM, INCLUDED -> price != null
                    ? price + (isNotBlank(charge.getUnit()) ? " / " + charge.getUnit() : "")
                    : "Theo thỏa thuận";
        };
    }

    private static String chargeAmountAndMethodStr(ListingSnapshotDto.ChargeItemSnapshot charge, int occupants,
            int motorbikes, int cars, BigDecimal areaM2) {
        if (charge.isIncludedInRent() || "INCLUDED".equalsIgnoreCase(charge.getBillingMethod())) {
            return "Đã bao gồm trong giá thuê";
        }

        String method = charge.getBillingMethod();
        BigDecimal amount = charge.getAmount();
        String price = amount != null ? ContractRenderService.formatVND(amount) : null;

        if (method == null) {
            return price != null ? price : "Theo thỏa thuận";
        }

        return switch (method.toUpperCase()) {
            case "FREE" -> "Miễn phí";
            case "NOT_APPLICABLE" -> "Bên thuê tự đăng ký với đơn vị cung cấp";
            case "NEGOTIABLE" -> "Theo thỏa thuận giữa hai bên";
            case "STATE_WATER_RATE" -> "Theo đơn giá nhà nước";
            case "PER_KWH" -> price == null ? "Theo chỉ số công tơ" : price + " / kWh (theo chỉ số công tơ)";
            case "PER_M3" -> price == null ? "Theo chỉ số đồng hồ" : price + " / m³ (theo chỉ số đồng hồ)";
            case "PER_HOUR" -> price == null ? "Theo giờ sử dụng" : price + " / giờ";
            case "PER_VEHICLE_MONTH" -> {
                int count = "MOTORBIKE_PARKING".equalsIgnoreCase(charge.getChargeType()) ? motorbikes : cars;
                if (price == null)
                    yield "Theo số xe đăng ký";
                BigDecimal total = amount != null ? amount.multiply(BigDecimal.valueOf(count)) : BigDecimal.ZERO;
                yield price + " / xe / tháng × " + count + " xe = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case "PER_MONTH" -> price == null ? "Theo tháng" : price + " / tháng";
            case "PER_PERSON_MONTH" -> {
                if (amount == null)
                    yield "Theo số người ở";
                BigDecimal total = amount.multiply(BigDecimal.valueOf(occupants));
                yield price + " / người / tháng × " + occupants + " người = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            case "PER_M2_MONTH" -> {
                if (amount == null)
                    yield "Theo diện tích";
                if (areaM2 == null)
                    yield price + " / m² / tháng";
                BigDecimal total = amount.multiply(areaM2);
                yield price + " / m² / tháng × " + stripTrailingZeros(areaM2) + " m² = "
                        + ContractRenderService.formatVND(total) + " / tháng";
            }
            default -> price != null
                    ? price + (isNotBlank(charge.getUnit()) ? " / " + charge.getUnit() : "")
                    : "Theo thỏa thuận";
        };
    }

    public static BigDecimal estimateMonthlyAmount(ListingCharge charge, int occupants, BigDecimal areaM2) {
        return estimateMonthlyAmount(charge, occupants, 0, 0, areaM2);
    }

    public static BigDecimal estimateMonthlyAmount(ListingCharge charge, int occupants, int motorbikes, int cars,
            BigDecimal areaM2) {
        if (charge.isIncludedInRent() || charge.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        BillingMethod method = charge.getBillingMethod();
        if (method == null) {
            return null;
        }
        return switch (method) {
            case FREE, INCLUDED -> BigDecimal.ZERO;
            case PER_MONTH -> charge.getAmount();
            case PER_PERSON_MONTH -> charge.getAmount().multiply(BigDecimal.valueOf(occupants));
            case PER_M2_MONTH -> areaM2 == null ? null : charge.getAmount().multiply(areaM2);
            case PER_VEHICLE_MONTH -> {
                if (charge.getChargeType() == ChargeType.MOTORBIKE_PARKING) {
                    yield charge.getAmount().multiply(BigDecimal.valueOf(motorbikes));
                }
                if (charge.getChargeType() == ChargeType.CAR_PARKING) {
                    yield charge.getAmount().multiply(BigDecimal.valueOf(cars));
                }
                yield null;
            }
            case PER_KWH, PER_M3, STATE_WATER_RATE, PER_HOUR,
                    NOT_APPLICABLE, NEGOTIABLE, CUSTOM ->
                null;
        };
    }

    public static BigDecimal estimateMonthlyAmountStr(ListingSnapshotDto.ChargeItemSnapshot charge, int occupants,
            int motorbikes, int cars, BigDecimal areaM2) {
        if (charge.isIncludedInRent() || charge.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        String method = charge.getBillingMethod();
        if (method == null)
            return null;

        return switch (method.toUpperCase()) {
            case "FREE", "INCLUDED" -> BigDecimal.ZERO;
            case "PER_MONTH" -> charge.getAmount();
            case "PER_PERSON_MONTH" -> charge.getAmount().multiply(BigDecimal.valueOf(occupants));
            case "PER_M2_MONTH" -> areaM2 == null ? null : charge.getAmount().multiply(areaM2);
            case "PER_VEHICLE_MONTH" -> {
                if ("MOTORBIKE_PARKING".equalsIgnoreCase(charge.getChargeType())) {
                    yield charge.getAmount().multiply(BigDecimal.valueOf(motorbikes));
                }
                if ("CAR_PARKING".equalsIgnoreCase(charge.getChargeType())) {
                    yield charge.getAmount().multiply(BigDecimal.valueOf(cars));
                }
                yield null;
            }
            default -> null;
        };
    }
}
