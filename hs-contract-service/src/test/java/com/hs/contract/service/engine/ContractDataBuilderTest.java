package com.hs.contract.service.engine;

import com.hs.listing.model.Amenity;
import com.hs.listing.model.ListingFurnishingAsset;
import com.hs.listing.model.PropertyBranch;
import com.hs.listing.model.constant.HandoverCondition;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.model.constant.ListingEnums;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingCharge;
import com.hs.listing.model.RentalRequest;
import com.hs.listing.model.constant.ListingEnums.BillingMethod;
import com.hs.listing.model.constant.ListingEnums.ChargeType;
import com.hs.user.model.Address;
import com.hs.user.repository.AddressRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContractDataBuilderTest {
    @Test void capturedListingDoesNotImportLaterAmenitiesInventoryFeesOrOptionalCapacity() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var snapshot = com.hs.listing.dto.snapshot.ListingSnapshotDto.builder().listingId("listing")
                .category(ListingCategory.ROOM).fullAddress("Địa chỉ đã chốt").areaM2(new BigDecimal("25"))
                .priceAmount(new BigDecimal("2000000")).depositType("NONE")
                .roomDetail(com.hs.listing.dto.snapshot.ListingSnapshotDto.RoomDetailSnapshot.builder()
                        .roomCode("101").restroomType("SHARED").kitchenType("NONE").balconyType("SHARED")
                        .electricMeterType("PRIVATE").waterMeterType("SHARED").build()).build();
        var request = RentalRequest.builder().listingSnapshot(mapper.writeValueAsString(snapshot))
                .occupantCount(1).moveInDate(java.time.LocalDate.of(2026,10,14)).leaseMonths(3).build();
        var house = new com.hs.listing.model.ListingHouseDetail(); house.setMaxOccupants(20); house.setTotalFloors(5);
        var laterAsset = new ListingFurnishingAsset(); laterAsset.setAssetName("Tủ lạnh mới"); laterAsset.setQuantity(2);
        var listing = Listing.builder().category(ListingCategory.HOUSE).areaM2(new BigDecimal("300"))
                .houseDetail(house).furnishings(List.of(laterAsset)).build();
        var builder = new ContractDataBuilder(mock(UserRepository.class),mock(AddressRepository.class),mapper);
        var result = builder.build(request,listing);
        assertEquals("Phòng trọ",result.getProperty().get("propertyType"));
        assertEquals("Địa chỉ đã chốt",result.getProperty().get("fullAddress"));
        assertEquals("25",result.getProperty().get("areaM2")); assertEquals("",result.getProperty().get("maxOccupants"));
        assertTrue(result.getEquipments().isEmpty()); assertTrue(result.getCharges().isEmpty());
        assertEquals("0",result.getFinancial().get("depositAmountValue"));
        assertEquals("13/01/2027",result.getLease().get("endDateText"));
        request.setListingSnapshot("broken-json");
        org.junit.jupiter.api.Assertions.assertThrows(com.hs.common.advice.entity.AppException.class,() -> builder.build(request,listing));
    }
    @Test void newContractUsesSameAmenitySourceAsAmendmentAndDoesNotClaimPaidWifiIsIncluded() {
        var wifi = new Amenity(); wifi.setCode("WIFI"); wifi.setName("WiFi");
        var dry = new com.hs.listing.model.ListingCustomAmenity(); dry.setName("Sân phơi chung");
        var asset = new ListingFurnishingAsset(); asset.setAssetName("Giường"); asset.setQuantity(2);
        asset.setHandoverCondition(HandoverCondition.GOOD); asset.setConditionNote("Xước đầu giường");
        var fee = new ListingCharge(); fee.setChargeType(ChargeType.INTERNET); fee.setBillingMethod(BillingMethod.PER_MONTH); fee.setAmount(new BigDecimal("90000"));
        var branch = PropertyBranch.builder().buildingAmenities(Set.of(wifi)).buildingRules("Không gây ồn sau 22 giờ").build();
        var listing = Listing.builder().category(ListingCategory.APARTMENT).amenities(Set.of(wifi))
                .branch(branch).customAmenities(List.of(dry)).furnishings(List.of(asset)).charges(List.of(fee)).build();
        var result = new ContractDataBuilder(mock(UserRepository.class),mock(AddressRepository.class)).build(RentalRequest.builder().occupantCount(1).build(),listing);
        var wifiRow = result.getAmenities().stream().filter(a -> "WIFI".equals(a.get("code"))).findFirst().orElseThrow();
        assertEquals("LISTING",wifiRow.get("sourceType"));
        assertEquals(Set.of("index","code","name","sourceType"),wifiRow.keySet());
        assertTrue(result.getCharges().getFirst().get("amountAndMethod").toString().contains("90.000"));
        assertEquals("2",result.getEquipments().getFirst().get("quantity"));
        assertEquals("Xước đầu giường",result.getEquipments().getFirst().get("note"));
    }

    @Test
    void waterPerPersonUsesOccupantCountAndDoesNotRequireInitialMeter() {
        ListingCharge water = new ListingCharge();
        water.setChargeType(ChargeType.WATER);
        water.setBillingMethod(BillingMethod.PER_PERSON_MONTH);
        water.setAmount(new BigDecimal("100000"));
        water.setCurrency("VND");
        water.setSortOrder(0);

        Listing listing = new Listing();
        listing.setCharges(List.of(water));

        RentalRequest request = RentalRequest.builder()
                .ownerId("landlord-id")
                .renterId("tenant-id")
                .occupantCount(3)
                .build();

        UserRepository userRepository = mock(UserRepository.class);
        AddressRepository addressRepository = mock(AddressRepository.class);
        Address landlordAddress = new Address();
        landlordAddress.setDisplay("12 Nguyễn Huệ, Phường Sài Gòn, TP. Hồ Chí Minh");
        when(addressRepository.findByUser_IdAndActiveTrue("landlord-id"))
                .thenReturn(Optional.of(landlordAddress));

        ContractDataBuilder builder = new ContractDataBuilder(userRepository, addressRepository);

        ContractDataBuilder.ContractSnapshots snapshots = builder.build(request, listing);

        assertTrue(String.valueOf(snapshots.getCharges().getFirst().get("amountAndMethod"))
                .contains("3 người = 300.000 VNĐ / tháng"));
        assertEquals("300000", snapshots.getCharges().getFirst().get("estimatedMonthlyAmount"));
        assertEquals(
                "Không áp dụng - nước tính theo 3 người (300.000 VNĐ/tháng)",
                snapshots.getMeters().get("waterInitial")
        );
        assertEquals(
                "12 Nguyễn Huệ, Phường Sài Gòn, TP. Hồ Chí Minh",
                snapshots.getLandlord().get("permanentAddress")
        );
        assertEquals(3, snapshots.getTenant().get("occupantCount"));
        assertEquals(0, snapshots.getTenant().get("motorbikeCount"));
        assertEquals(0, snapshots.getTenant().get("carCount"));
        assertEquals(4, snapshots.getFinancial().get("paymentDueOffsetDays"));
        assertTrue(String.valueOf(snapshots.getFinancial().get("paymentDueDay"))
                .contains("ngày thứ 5 tính từ ngày chốt kỳ"));
        assertEquals(snapshots.getFinancial().get("paymentDueDay"),
                snapshots.getPolicies().get("paymentDueDay"));
        assertTrue(snapshots.getSpecialTerms().contains("Sau khi hết 05 ngày quá hạn"));
        assertTrue(snapshots.getSpecialTerms().contains("tối đa 10 ngày"));
        assertTrue(snapshots.getSpecialTerms().contains("bên cho thuê vẫn có thể thực hiện quyền chấm dứt đã thỏa thuận"));
        assertEquals(true, snapshots.getPolicies().get("overdueLandlordTerminationAfterFiveDays"));
        assertTrue(String.valueOf(snapshots.getFinancial().get("depositDescription"))
                .contains("điều khoản vi phạm hoặc chấm dứt"));

        // Verify rentalMode and B2B tenant fields are absent
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getLease().containsKey("rentalMode"),
                "Lease snapshot must not contain rentalMode");
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getTenant().containsKey("organizationName"),
                "Tenant snapshot must not contain organizationName");
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getTenant().containsKey("representativeName"),
                "Tenant snapshot must not contain representativeName");
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getTenant().containsKey("representativePosition"),
                "Tenant snapshot must not contain representativePosition");
    }

    @Test
    void categoryLabelsMatchStandards() {
        assertEquals("Nhà nguyên căn", ContractDataBuilder.categoryLabel(com.hs.listing.model.constant.ListingCategory.HOUSE));
        assertEquals("Căn hộ chung cư", ContractDataBuilder.categoryLabel(com.hs.listing.model.constant.ListingCategory.APARTMENT));
        assertEquals("Phòng trọ", ContractDataBuilder.categoryLabel(com.hs.listing.model.constant.ListingCategory.ROOM));
    }

    @Test
    void buildApartment_hasCorrectPropertySnapshotAndNoBranchLeaks() {
        Listing listing = new Listing();
        listing.setId("listing-apt-1");
        listing.setCategory(ListingCategory.APARTMENT);
        listing.setAreaM2(new BigDecimal("75.5"));
        listing.setPriceAmount(new BigDecimal("12000000"));

        com.hs.listing.model.ListingApartmentDetail apt = new com.hs.listing.model.ListingApartmentDetail();
        apt.setProjectName("Vinhomes Grand Park");
        apt.setBuildingBlock("S1.02");
        apt.setUnitCode("1204");
        apt.setFloorNumber(12);
        apt.setBuildingTotalFloors(25);
        apt.setBedroomCount(2);
        apt.setBathroomCount(2);
        apt.setMaxOccupants(4);
        listing.setApartmentDetail(apt);

        RentalRequest request = RentalRequest.builder()
                .id("req-apt-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .occupantCount(3)
                .effectiveMonthlyRent(new BigDecimal("12000000"))
                .depositAmount(new BigDecimal("12000000"))
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        assertEquals("Vinhomes Grand Park", snapshots.getProperty().get("projectName"));
        assertEquals("S1.02", snapshots.getProperty().get("buildingBlock"));
        assertEquals("Căn hộ 1204", snapshots.getProperty().get("unitNumber"));
        assertEquals("Tầng 12", snapshots.getProperty().get("floor"));
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("branchId"));
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("branchName"));
    }

    @Test
    void buildHouse_hasCorrectRentalScopeAndFloors() {
        Listing listing = new Listing();
        listing.setId("listing-house-1");
        listing.setCategory(ListingCategory.HOUSE);
        listing.setAreaM2(new BigDecimal("120.0"));
        listing.setPriceAmount(new BigDecimal("25000000"));

        com.hs.listing.model.ListingHouseDetail house = new com.hs.listing.model.ListingHouseDetail();
        house.setTotalFloors(3);
        house.setBedroomCount(4);
        house.setBathroomCount(3);
        house.setRentalScopeDescription("Toàn bộ tầng 1 và tầng 2, có sân để xe riêng");
        house.setRentedFloorFrom(1);
        house.setRentedFloorTo(2);
        listing.setHouseDetail(house);

        RentalRequest request = RentalRequest.builder()
                .id("req-house-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .occupantCount(4)
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        assertEquals("Toàn bộ tầng 1 và tầng 2, có sân để xe riêng", snapshots.getProperty().get("rentalScope"));
        assertEquals("3", snapshots.getProperty().get("totalFloors"));
        assertEquals(4, snapshots.getProperty().get("bedroomCount"));
    }

    @Test
    void buildRoom_hasCorrectRoomCodeAndMeters() {
        Listing listing = new Listing();
        listing.setId("listing-room-1");
        listing.setCategory(ListingCategory.ROOM);
        listing.setAreaM2(new BigDecimal("25.0"));
        listing.setPriceAmount(new BigDecimal("3500000"));

        com.hs.listing.model.ListingRoomDetail room = new com.hs.listing.model.ListingRoomDetail();
        room.setRoomCode("P.302");
        room.setFloorNumber(3);
        room.setElectricMeterType(ListingEnums.MeterType.PRIVATE);
        room.setWaterMeterType(ListingEnums.MeterType.SHARED);
        room.setRestroomType(ListingEnums.RestroomType.PRIVATE);
        room.setMaxOccupants(4);
        room.setMaxVehicles(2);
        listing.setRoomDetail(room);

        RentalRequest request = RentalRequest.builder()
                .id("req-room-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .occupantCount(2)
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        assertEquals("Phòng P.302", snapshots.getProperty().get("unitNumber"));
        assertEquals("Tầng 3", snapshots.getProperty().get("floor"));
        assertEquals(4, snapshots.getProperty().get("maxOccupants"));
        assertEquals(2, snapshots.getProperty().get("maxVehicles"));
        assertEquals("Công tơ / đồng hồ riêng từng phòng", snapshots.getMeters().get("electricMeterType"));
        assertEquals("Dùng chung / chia đều", snapshots.getMeters().get("waterMeterType"));
    }

    @Test
    void branchListing_neverLeaksBranchIdOrNameOrCapacity() {
        Listing listing = new Listing();
        listing.setId("listing-branch-1");
        listing.setBranchId("branch-uuid-12345");
        PropertyBranch branch = new PropertyBranch();
        branch.setId("branch-uuid-12345");
        branch.setName("Tòa nhà Cầu Giấy Complex");
        branch.setCode("CG-01");
        branch.setTotalUnits(100);
        branch.setMotorbikeParkingCapacity(200);
        branch.setCarParkingCapacity(50);
        listing.setBranch(branch);

        RentalRequest request = RentalRequest.builder()
                .id("req-branch-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .occupantCount(2)
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        // Verify that branchId, branchName, branchCode, branch capacities are absent
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("branchId"));
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("branchName"));
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("branchCode"));
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("motorbikeParkingCapacity"));
        org.junit.jupiter.api.Assertions.assertFalse(snapshots.getProperty().containsKey("carParkingCapacity"));

        for (var amenity : snapshots.getAmenities()) {
            org.junit.jupiter.api.Assertions.assertFalse(String.valueOf(amenity.get("name")).contains("Cầu Giấy Complex"));
            org.junit.jupiter.api.Assertions.assertFalse(String.valueOf(amenity.get("scope")).contains("Cầu Giấy Complex"));
        }
    }

    @Test
    void furnishingAndAmenityKeepBothExplicitDatabaseSelections() {
        Listing listing = new Listing();
        listing.setId("listing-furn-1");

        // Amenity: AIR_CONDITIONER
        Amenity am = new Amenity();
        am.setCode("AIR_CONDITIONER");
        am.setName("Máy lạnh");
        listing.setAmenities(Set.of(am));

        // Furnishing: AIR_CONDITIONER
        ListingFurnishingAsset fa = new ListingFurnishingAsset();
        fa.setItemCode("AIR_CONDITIONER");
        fa.setAssetName("Máy lạnh");
        fa.setQuantity(2);
        fa.setHandoverCondition(HandoverCondition.GOOD);
        listing.setFurnishings(List.of(fa));

        RentalRequest request = RentalRequest.builder()
                .id("req-dedup-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        // Equipment table should have Máy lạnh
        assertEquals(1, snapshots.getEquipments().size());
        assertEquals("Máy lạnh", snapshots.getEquipments().getFirst().get("name"));
        assertEquals("2", snapshots.getEquipments().getFirst().get("quantity"));

        // Both tables have their own database selections: do not silently drop either.
        boolean hasAcInAmenities = snapshots.getAmenities().stream()
                .anyMatch(a -> "AIR_CONDITIONER".equals(a.get("code")));
        assertTrue(hasAcInAmenities, "Selected amenities must match the listing even when also inventoried");
    }

    @Test
    void petsAllowed_createsPolicyWithTenantObligations() {
        Listing listing = new Listing();
        listing.setId("listing-pets-1");

        Amenity am = new Amenity();
        am.setCode("PETS_ALLOWED");
        am.setName("Cho phép nuôi thú cưng");
        listing.setAmenities(Set.of(am));
        var policy = new com.hs.listing.model.ListingPolicyValue(); policy.setListing(listing);
        policy.setDefinition(com.hs.listing.config.ListingPolicyCatalog.defaults().stream().filter(d -> d.getCode().equals("petPolicy")).findFirst().orElseThrow());
        policy.setValue("ALLOWED"); policy.setNote("Giữ vệ sinh, hạn chế tiếng ồn và bồi thường thiệt hại.");
        listing.setPolicies(List.of(policy));

        RentalRequest request = RentalRequest.builder()
                .id("req-pets-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        var petAmenity = snapshots.getAmenities().stream()
                .filter(a -> "PETS_ALLOWED".equals(a.get("code")))
                .findFirst();

        org.junit.jupiter.api.Assertions.assertFalse(petAmenity.isPresent(), "Pets must be a policy, not an amenity");
        assertEquals(true,snapshots.getPolicies().get("petsAllowed"));
        String conditions = String.valueOf(snapshots.getPolicies().get("petPolicy"));
        assertTrue(conditions.contains("vệ sinh"), "Condition must mention hygiene");
        assertTrue(conditions.contains("tiếng ồn"), "Condition must mention noise");
        assertTrue(conditions.contains("bồi thường"), "Condition must mention damage compensation");
    }

    @Test
    void parkingCharges_calculatesForMotorbikesAndCarsCorrectly() {
        Listing listing = new Listing();
        listing.setId("listing-parking-1");

        ListingCharge bikeCharge = new ListingCharge();
        bikeCharge.setCustomName("Phí gửi xe máy");
        bikeCharge.setChargeType(ChargeType.MOTORBIKE_PARKING);
        bikeCharge.setBillingMethod(BillingMethod.PER_VEHICLE_MONTH);
        bikeCharge.setAmount(new BigDecimal("120000"));
        bikeCharge.setCurrency("VND");

        ListingCharge carCharge = new ListingCharge();
        carCharge.setCustomName("Phí gửi ô tô");
        carCharge.setChargeType(ChargeType.CAR_PARKING);
        carCharge.setBillingMethod(BillingMethod.PER_VEHICLE_MONTH);
        carCharge.setAmount(new BigDecimal("1200000"));
        carCharge.setCurrency("VND");

        listing.setCharges(List.of(bikeCharge, carCharge));

        RentalRequest request = RentalRequest.builder()
                .id("req-parking-1")
                .ownerId("landlord-1")
                .renterId("tenant-1")
                .motorbikeCount(2)
                .carCount(1)
                .build();

        ContractDataBuilder builder = new ContractDataBuilder(mock(UserRepository.class), mock(AddressRepository.class));
        var snapshots = builder.build(request, listing);

        assertEquals(2, snapshots.getTenant().get("motorbikeCount"));
        assertEquals(1, snapshots.getTenant().get("carCount"));

        var bikeRow = snapshots.getCharges().stream().filter(c -> "Phí gửi xe máy".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("240000", bikeRow.get("estimatedMonthlyAmount"));

        var carRow = snapshots.getCharges().stream().filter(c -> "Phí gửi ô tô".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("1200000", carRow.get("estimatedMonthlyAmount"));
    }
}
