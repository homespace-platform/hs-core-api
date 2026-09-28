package com.hs.listing.service;
import com.hs.common.advice.entity.AppException; import com.hs.listing.repository.*; import com.hs.storage.repository.StorageObjectRepository; import com.hs.user.repository.AddressRepository; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class ListingServiceTest {
    @Test
    void rejectsUnauthenticatedUpsert() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));
        AppException ex = assertThrows(AppException.class, () -> service.upsert(null, null));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void rejectsOfficeAndCommercialSpaceCategories() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(10000000), "VND", com.hs.listing.model.constant.PriceUnit.MONTH,
                false, com.hs.listing.model.constant.DepositType.FIXED_AMOUNT,
                java.math.BigDecimal.valueOf(20000000), null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 12, false, null);

        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW, null, null);

        var officeReq = new com.hs.listing.dto.request.CreateListingRequest(
                null, null, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Office test", "Office test description",
                com.hs.listing.model.constant.ListingCategory.OFFICE,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(50),
                null, null,
                pricing, null, null, null, null, null, null, null, null, null,
                addressSource, java.util.List.of(), java.util.List.of(), java.util.List.of());

        AppException exOffice = assertThrows(AppException.class, () -> service.upsert("owner-1", officeReq));
        assertEquals(400, exOffice.getStatusCode().value());
        assertTrue(exOffice.getMessage().contains("Only HOUSE, APARTMENT, and ROOM are supported"));

        var commercialReq = new com.hs.listing.dto.request.CreateListingRequest(
                null, null, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Commercial test", "Commercial test description",
                com.hs.listing.model.constant.ListingCategory.COMMERCIAL_SPACE,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(50),
                null, null,
                pricing, null, null, null, null, null, null, null, null, null,
                addressSource, java.util.List.of(), java.util.List.of(), java.util.List.of());

        AppException exComm = assertThrows(AppException.class, () -> service.upsert("owner-1", commercialReq));
        assertEquals(400, exComm.getStatusCode().value());
        assertTrue(exComm.getMessage().contains("Only HOUSE, APARTMENT, and ROOM are supported"));
    }

    @Test
    void verifyNoRentalModeOrSubtypeInRequestAndResponse() {
        assertFalse(hasDeclaredField(com.hs.listing.dto.request.CreateListingRequest.class, "rentalMode"));
        assertFalse(hasDeclaredField(com.hs.listing.dto.request.CreateListingRequest.class, "subtype"));
        assertFalse(hasDeclaredField(com.hs.listing.dto.response.ListingDetailResponse.class, "rentalMode"));
        assertFalse(hasDeclaredField(com.hs.listing.dto.response.ListingDetailResponse.class, "subtype"));
        assertFalse(hasDeclaredField(com.hs.listing.model.Listing.class, "rentalMode"));
        assertFalse(hasDeclaredField(com.hs.listing.model.Listing.class, "subtype"));
    }

    @Test
    void rejectsListingWithIncompleteBranchCharges() {
        var branchRepo = mock(PropertyBranchRepository.class);
        var listingRepo = mock(ListingRepository.class);
        var addressRepo = mock(AddressRepository.class);
        var storageRepo = mock(StorageObjectRepository.class);
        var amenityRepo = mock(AmenityRepository.class);
        var furnishingRepo = mock(FurnishingItemRepository.class);
        var statusService = mock(ListingStatusService.class);

        ListingService service = new ListingService(listingRepo, addressRepo, storageRepo, amenityRepo, furnishingRepo, statusService, branchRepo);

        String branchId = "branch-incomplete";
        com.hs.listing.model.PropertyBranch branch = com.hs.listing.model.PropertyBranch.builder()
                .id(branchId)
                .ownerId("owner-1")
                .name("Chi nhánh Thiếu Phí")
                .category(com.hs.listing.model.constant.ListingCategory.ROOM)
                .defaultCharges(java.util.List.of(
                        com.hs.listing.model.BranchCharge.builder().chargeType(com.hs.listing.model.constant.ListingEnums.ChargeType.ELECTRICITY).billingMethod(com.hs.listing.model.constant.ListingEnums.BillingMethod.PER_KWH).build()
                ))
                .build();
        branch.setActive(true);

        when(branchRepo.findByIdAndOwnerIdAndActiveTrue(branchId, "owner-1")).thenReturn(java.util.Optional.of(branch));
        when(branchRepo.findByIdAndActiveTrue(branchId)).thenReturn(java.util.Optional.of(branch));

        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(5000000), "VND", com.hs.listing.model.constant.PriceUnit.MONTH,
                false, com.hs.listing.model.constant.DepositType.FIXED_AMOUNT,
                java.math.BigDecimal.valueOf(5000000), null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 12, false, null);

        var address = new com.hs.listing.dto.request.ListingAddressRequest("P1", "Province", "W1", "Ward", "123 Street", "123 Street, Ward, Province");
        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW,
                null, address);

        var roomDetail = new com.hs.listing.dto.request.RoomDetailRequest(
                "R101", 1, com.hs.listing.model.constant.ListingEnums.RestroomType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.KitchenType.PRIVATE, true,
                com.hs.listing.model.constant.ListingEnums.BalconyType.PRIVATE, false,
                com.hs.listing.model.constant.FurnishingStatus.UNFURNISHED,
                com.hs.listing.model.constant.ListingEnums.AccessType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.AccessHoursType.FLEXIBLE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                2, 2, com.hs.listing.model.constant.ListingEnums.ParkingPolicy.FREE);

        var mediaReq = new com.hs.listing.dto.request.ListingMediaRequest(
                "storage-1", com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 0, true);
        var storageObj = com.hs.storage.model.StorageObject.builder()
                .id("storage-1")
                .ownerId("owner-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)
                .contentType("image/jpeg")
                .objectKey("listings/test.jpg")
                .build();
        when(storageRepo.findById("storage-1")).thenReturn(java.util.Optional.of(storageObj));

        var req = new com.hs.listing.dto.request.CreateListingRequest(
                null, branchId, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Room test", "Room test description",
                com.hs.listing.model.constant.ListingCategory.ROOM,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(25),
                null, null,
                pricing, null, null, null, null, roomDetail, null, null, null, null,
                addressSource, java.util.List.of(mediaReq), java.util.List.of(), java.util.List.of());

        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", req));
        assertEquals(com.hs.listing.advice.ListingErrorCode.BRANCH_CHARGES_INCOMPLETE.getCode(), ex.getCode());
    }

    @Test
    void testUpsert_rejectsAvailableFromInPast() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(3500000), "VND",
                com.hs.listing.model.constant.PriceUnit.MONTH, false,
                com.hs.listing.model.constant.DepositType.NONE, null, null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 6, false, false);

        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW, null, null);

        var roomDetail = new com.hs.listing.dto.request.RoomDetailRequest(
                "R101", 1, com.hs.listing.model.constant.ListingEnums.RestroomType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.KitchenType.PRIVATE, true,
                com.hs.listing.model.constant.ListingEnums.BalconyType.PRIVATE, false,
                com.hs.listing.model.constant.FurnishingStatus.UNFURNISHED,
                com.hs.listing.model.constant.ListingEnums.AccessType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.AccessHoursType.FLEXIBLE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                2, 2, com.hs.listing.model.constant.ListingEnums.ParkingPolicy.FREE);

        var pastReq = new com.hs.listing.dto.request.CreateListingRequest(
                null, null, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Room past test", "Room test description",
                com.hs.listing.model.constant.ListingCategory.ROOM,
                java.time.LocalDate.now().minusDays(1), java.math.BigDecimal.valueOf(25),
                null, null,
                pricing, null, null, null, null, roomDetail, null, null, null, null,
                addressSource, java.util.List.of(), java.util.List.of(), java.util.List.of());

        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", pastReq));
        assertEquals(422, ex.getStatusCode().value());
        assertTrue(ex.getMessage().contains("CANNOT_BE_IN_PAST"));
    }

    private boolean hasDeclaredField(Class<?> clazz, String fieldName) {
        try {
            clazz.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
