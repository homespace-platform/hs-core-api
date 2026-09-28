package com.hs.listing.service;
import com.hs.common.advice.entity.AppException; import com.hs.listing.repository.*; import com.hs.storage.repository.StorageObjectRepository; import com.hs.user.repository.AddressRepository; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class ListingServiceTest {
    @Test
    void rejectsUnauthenticatedUpsert() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));
        AppException ex = assertThrows(AppException.class, () -> service.upsert(null, null));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void rejectsOfficeAndCommercialSpaceCategories() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
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

        ListingService service = new ListingService(listingRepo, addressRepo, storageRepo, mock(com.hs.storage.service.StorageService.class), amenityRepo, furnishingRepo, statusService, branchRepo);

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
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
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

    @Test
    void upsert_duplicateListing_copiesMediaAndCreatesNewIndependentListing() {
        var listingRepo = mock(ListingRepository.class);
        var addressRepo = mock(AddressRepository.class);
        var storageRepo = mock(StorageObjectRepository.class);
        var storageService = mock(com.hs.storage.service.StorageService.class);
        var amenityRepo = mock(AmenityRepository.class);
        var furnishingRepo = mock(FurnishingItemRepository.class);
        var statusService = mock(ListingStatusService.class);
        var branchRepo = mock(PropertyBranchRepository.class);

        ListingService service = new ListingService(listingRepo, addressRepo, storageRepo, storageService,
                amenityRepo, furnishingRepo, statusService, branchRepo);

        // Source listing with 2 photos and 1 video
        String sourceId = "source-listing-1";
        com.hs.listing.model.Listing sourceListing = new com.hs.listing.model.Listing();
        sourceListing.setId(sourceId);
        sourceListing.setOwnerId("owner-1");
        sourceListing.setStatus(com.hs.listing.model.constant.ListingStatus.PUBLISHED);

        var srcStorageImg1 = com.hs.storage.model.StorageObject.builder()
                .id("storage-img-1").ownerId("owner-1").status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE).contentType("image/jpeg")
                .objectKey("listing_image/owner-1/img-1.jpg").build();
        srcStorageImg1.setActive(true);

        var srcStorageImg2 = com.hs.storage.model.StorageObject.builder()
                .id("storage-img-2").ownerId("owner-1").status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE).contentType("image/jpeg")
                .objectKey("listing_image/owner-1/img-2.jpg").build();
        srcStorageImg2.setActive(true);

        var srcStorageVid = com.hs.storage.model.StorageObject.builder()
                .id("storage-vid-1").ownerId("owner-1").status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_VIDEO).contentType("video/mp4")
                .objectKey("listing_video/owner-1/vid-1.mp4").build();
        srcStorageVid.setActive(true);

        var media1 = new com.hs.listing.model.ListingMedia();
        media1.setId("media-1"); media1.setStorageObject(srcStorageImg1); media1.setMediaType(com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE);
        var media2 = new com.hs.listing.model.ListingMedia();
        media2.setId("media-2"); media2.setStorageObject(srcStorageImg2); media2.setMediaType(com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE);
        var mediaVid = new com.hs.listing.model.ListingMedia();
        mediaVid.setId("media-vid"); mediaVid.setStorageObject(srcStorageVid); mediaVid.setMediaType(com.hs.listing.model.constant.ListingEnums.MediaType.VIDEO);

        sourceListing.getMedia().addAll(java.util.List.of(media1, media2, mediaVid));
        when(listingRepo.findByIdAndActiveTrue(sourceId)).thenReturn(java.util.Optional.of(sourceListing));

        // Mock copying for media-1 and media-vid (dropping media-2)
        var copiedStorageImg1 = com.hs.storage.model.StorageObject.builder()
                .id("copied-storage-img-1").ownerId("owner-1").status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE).contentType("image/jpeg")
                .objectKey("listing_image/owner-1/copied-img-1.jpg").build();
        copiedStorageImg1.setActive(true);

        var copiedStorageVid = com.hs.storage.model.StorageObject.builder()
                .id("copied-storage-vid").ownerId("owner-1").status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_VIDEO).contentType("video/mp4")
                .objectKey("listing_video/owner-1/copied-vid.mp4").build();
        copiedStorageVid.setActive(true);

        // Also 1 newly uploaded photo
        var newUploadedImg = com.hs.storage.model.StorageObject.builder()
                .id("new-upload-storage").ownerId("owner-1").status(com.hs.storage.model.constant.StorageStatus.READY)
                .purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE).contentType("image/jpeg")
                .objectKey("listing_image/owner-1/new.jpg").build();
        newUploadedImg.setActive(true);

        when(storageService.copyObject(eq("storage-img-1"), eq("owner-1"), eq("LISTING"), any(), eq(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)))
                .thenReturn(new com.hs.storage.dto.response.StorageObjectResponse("copied-storage-img-1", "img-1.jpg", "image/jpeg", 100L, null, "jpg", "owner-1", "LISTING", null, com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE, null, com.hs.storage.model.constant.StorageStatus.READY, null, null));
        when(storageRepo.findById("copied-storage-img-1")).thenReturn(java.util.Optional.of(copiedStorageImg1));

        when(storageService.copyObject(eq("storage-vid-1"), eq("owner-1"), eq("LISTING"), any(), eq(com.hs.storage.model.constant.StoragePurpose.LISTING_VIDEO)))
                .thenReturn(new com.hs.storage.dto.response.StorageObjectResponse("copied-storage-vid", "vid.mp4", "video/mp4", 500L, null, "mp4", "owner-1", "LISTING", null, com.hs.storage.model.constant.StoragePurpose.LISTING_VIDEO, null, com.hs.storage.model.constant.StorageStatus.READY, null, null));
        when(storageRepo.findById("copied-storage-vid")).thenReturn(java.util.Optional.of(copiedStorageVid));

        when(storageRepo.findById("new-upload-storage")).thenReturn(java.util.Optional.of(newUploadedImg));

        when(listingRepo.save(any(com.hs.listing.model.Listing.class))).thenAnswer(invocation -> {
            com.hs.listing.model.Listing saved = invocation.getArgument(0);
            saved.setId("new-listing-999");
            return saved;
        });

        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(4000000), "VND", com.hs.listing.model.constant.PriceUnit.MONTH,
                false, com.hs.listing.model.constant.DepositType.FIXED_AMOUNT,
                java.math.BigDecimal.valueOf(4000000), null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 12, false, null);

        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW,
                null, new com.hs.listing.dto.request.ListingAddressRequest("P1", "Province", "W1", "Ward", "123 Street", "123 Street, Ward, Province"));

        var roomDetail = new com.hs.listing.dto.request.RoomDetailRequest(
                "R202", 2, com.hs.listing.model.constant.ListingEnums.RestroomType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.KitchenType.PRIVATE, true,
                com.hs.listing.model.constant.ListingEnums.BalconyType.PRIVATE, false,
                com.hs.listing.model.constant.FurnishingStatus.UNFURNISHED,
                com.hs.listing.model.constant.ListingEnums.AccessType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.AccessHoursType.FLEXIBLE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                2, 2, com.hs.listing.model.constant.ListingEnums.ParkingPolicy.FREE);

        // Duplicate request: Keep media1 (photo), Keep mediaVid (video), Add new upload, omit media2
        var mediaReq1 = new com.hs.listing.dto.request.ListingMediaRequest(null, "media-1", com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 0, true);
        var mediaReq2 = new com.hs.listing.dto.request.ListingMediaRequest(null, "media-vid", com.hs.listing.model.constant.ListingEnums.MediaType.VIDEO, 1, false);
        var mediaReq3 = new com.hs.listing.dto.request.ListingMediaRequest("new-upload-storage", null, com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 2, false);

        var duplicateReq = new com.hs.listing.dto.request.CreateListingRequest(
                null, sourceId, null, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Bản sao của phòng R101", "Mô tả bản sao phòng",
                com.hs.listing.model.constant.ListingCategory.ROOM,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(25),
                null, null,
                pricing, null, null, null, null, roomDetail, null, null, null, null,
                addressSource, java.util.List.of(mediaReq1, mediaReq2, mediaReq3), java.util.List.of(), java.util.List.of());

        var response = service.upsert("owner-1", duplicateReq);

        assertNotNull(response);
        assertEquals("new-listing-999", response.id());

        // Verify independent storage objects: source still has 3 media items, new listing has 3 media items
        assertEquals(3, sourceListing.getMedia().size());
        assertEquals("storage-img-1", sourceListing.getMedia().get(0).getStorageObject().getId());

        // Verify copyObject was called for the 2 copied media items
        verify(storageService).copyObject(eq("storage-img-1"), eq("owner-1"), eq("LISTING"), any(), eq(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE));
        verify(storageService).copyObject(eq("storage-vid-1"), eq("owner-1"), eq("LISTING"), any(), eq(com.hs.storage.model.constant.StoragePurpose.LISTING_VIDEO));
        verify(storageService, never()).copyObject(eq("storage-img-2"), any(), any(), any(), any());
    }

    @Test
    void upsert_duplicateListing_forbiddenWhenSourceBelongsToAnotherOwner() {
        var listingRepo = mock(ListingRepository.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing foreignListing = new com.hs.listing.model.Listing();
        foreignListing.setId("source-foreign");
        foreignListing.setOwnerId("owner-other");
        when(listingRepo.findByIdAndActiveTrue("source-foreign")).thenReturn(java.util.Optional.of(foreignListing));

        var req = createDummyRequest("source-foreign", null);
        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", req));
        assertEquals(403, ex.getStatusCode().value());
        assertTrue(ex.getMessage().contains("Bạn không có quyền"));
    }

    @Test
    void upsert_duplicateListing_forbiddenWhenSourceIsViolation() {
        var listingRepo = mock(ListingRepository.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing violationListing = new com.hs.listing.model.Listing();
        violationListing.setId("source-violation");
        violationListing.setOwnerId("owner-1");
        violationListing.setStatus(com.hs.listing.model.constant.ListingStatus.VIOLATION);
        when(listingRepo.findByIdAndActiveTrue("source-violation")).thenReturn(java.util.Optional.of(violationListing));

        var req = createDummyRequest("source-violation", null);
        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", req));
        assertEquals(com.hs.listing.advice.ListingErrorCode.LISTING_LOCKED_BY_VIOLATION.getCode(), ex.getCode());
    }

    @Test
    void upsert_duplicateListing_rejectsWhenIdIsSpecified() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        var req = createDummyRequest("source-1", "cannot-have-id-in-duplicate");
        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", req));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void upsert_duplicateListing_rejectsForeignSourceMedia() {
        var listingRepo = mock(ListingRepository.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing sourceListing = new com.hs.listing.model.Listing();
        sourceListing.setId("source-1");
        sourceListing.setOwnerId("owner-1");
        sourceListing.setStatus(com.hs.listing.model.constant.ListingStatus.DRAFT);
        when(listingRepo.findByIdAndActiveTrue("source-1")).thenReturn(java.util.Optional.of(sourceListing));

        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(4000000), "VND", com.hs.listing.model.constant.PriceUnit.MONTH,
                false, com.hs.listing.model.constant.DepositType.FIXED_AMOUNT,
                java.math.BigDecimal.valueOf(4000000), null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 12, false, null);

        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW,
                null, new com.hs.listing.dto.request.ListingAddressRequest("P1", "Province", "W1", "Ward", "123 Street", "123 Street, Ward, Province"));

        var roomDetail = new com.hs.listing.dto.request.RoomDetailRequest(
                "R202", 2, com.hs.listing.model.constant.ListingEnums.RestroomType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.KitchenType.PRIVATE, true,
                com.hs.listing.model.constant.ListingEnums.BalconyType.PRIVATE, false,
                com.hs.listing.model.constant.FurnishingStatus.UNFURNISHED,
                com.hs.listing.model.constant.ListingEnums.AccessType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.AccessHoursType.FLEXIBLE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                2, 2, com.hs.listing.model.constant.ListingEnums.ParkingPolicy.FREE);

        // media references "media-non-existent"
        var mediaReq = new com.hs.listing.dto.request.ListingMediaRequest(null, "media-non-existent", com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 0, true);

        var req = new com.hs.listing.dto.request.CreateListingRequest(
                null, "source-1", null, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Title", "Description of property listing",
                com.hs.listing.model.constant.ListingCategory.ROOM,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(25),
                null, null,
                pricing, null, null, null, null, roomDetail, null, null, null, null,
                addressSource, java.util.List.of(mediaReq), java.util.List.of(), java.util.List.of());

        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", req));
        assertEquals(400, ex.getStatusCode().value());
        assertTrue(ex.getMessage().contains("Source media does not belong to the source listing"));
    }

    @Test
    void upsert_duplicateListing_s3CleanupOnError() {
        var listingRepo = mock(ListingRepository.class);
        var storageRepo = mock(StorageObjectRepository.class);
        var storageService = mock(com.hs.storage.service.StorageService.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                storageRepo, storageService,
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing sourceListing = new com.hs.listing.model.Listing();
        sourceListing.setId("source-cleanup");
        sourceListing.setOwnerId("owner-1");
        sourceListing.setStatus(com.hs.listing.model.constant.ListingStatus.DRAFT);

        var srcStorage1 = com.hs.storage.model.StorageObject.builder().id("st-1").ownerId("owner-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY).purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)
                .contentType("image/jpeg").objectKey("listing_image/owner-1/st1.jpg").build();
        srcStorage1.setActive(true);

        var srcStorage2 = com.hs.storage.model.StorageObject.builder().id("st-2").ownerId("owner-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY).purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)
                .contentType("image/jpeg").objectKey("listing_image/owner-1/st2.jpg").build();
        srcStorage2.setActive(true);

        var m1 = new com.hs.listing.model.ListingMedia(); m1.setId("m-1"); m1.setStorageObject(srcStorage1); m1.setMediaType(com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE);
        var m2 = new com.hs.listing.model.ListingMedia(); m2.setId("m-2"); m2.setStorageObject(srcStorage2); m2.setMediaType(com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE);
        sourceListing.getMedia().addAll(java.util.List.of(m1, m2));
        when(listingRepo.findByIdAndActiveTrue("source-cleanup")).thenReturn(java.util.Optional.of(sourceListing));

        // First copy succeeds and returns S3 key "listing_image/owner-1/copied1.jpg"
        when(storageService.copyObject(eq("st-1"), eq("owner-1"), eq("LISTING"), any(), eq(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)))
                .thenReturn(new com.hs.storage.dto.response.StorageObjectResponse("copied-1", "f1.jpg", "image/jpeg", 100L, null, "jpg", "owner-1", "LISTING", null, com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE, null, com.hs.storage.model.constant.StorageStatus.READY, null, null));
        var copiedStorage1 = com.hs.storage.model.StorageObject.builder().id("copied-1").ownerId("owner-1")
                .status(com.hs.storage.model.constant.StorageStatus.READY).purpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)
                .contentType("image/jpeg").objectKey("listing_image/owner-1/copied1.jpg").build();
        copiedStorage1.setActive(true);
        when(storageRepo.findById("copied-1")).thenReturn(java.util.Optional.of(copiedStorage1));

        // Second copy fails
        when(storageService.copyObject(eq("st-2"), eq("owner-1"), eq("LISTING"), any(), eq(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE)))
                .thenThrow(new AppException(502, "S3 error", org.springframework.http.HttpStatus.BAD_GATEWAY));

        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(4000000), "VND", com.hs.listing.model.constant.PriceUnit.MONTH,
                false, com.hs.listing.model.constant.DepositType.FIXED_AMOUNT,
                java.math.BigDecimal.valueOf(4000000), null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 12, false, null);

        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW,
                null, new com.hs.listing.dto.request.ListingAddressRequest("P1", "Province", "W1", "Ward", "123 Street", "123 Street, Ward, Province"));

        var roomDetail = new com.hs.listing.dto.request.RoomDetailRequest(
                "R202", 2, com.hs.listing.model.constant.ListingEnums.RestroomType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.KitchenType.PRIVATE, true,
                com.hs.listing.model.constant.ListingEnums.BalconyType.PRIVATE, false,
                com.hs.listing.model.constant.FurnishingStatus.UNFURNISHED,
                com.hs.listing.model.constant.ListingEnums.AccessType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.AccessHoursType.FLEXIBLE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                2, 2, com.hs.listing.model.constant.ListingEnums.ParkingPolicy.FREE);

        var req = new com.hs.listing.dto.request.CreateListingRequest(
                null, "source-cleanup", null, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT,
                "Title", "Description of property listing",
                com.hs.listing.model.constant.ListingCategory.ROOM,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(25),
                null, null,
                pricing, null, null, null, null, roomDetail, null, null, null, null,
                addressSource, java.util.List.of(
                        new com.hs.listing.dto.request.ListingMediaRequest(null, "m-1", com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 0, true),
                        new com.hs.listing.dto.request.ListingMediaRequest(null, "m-2", com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 1, false)),
                java.util.List.of(), java.util.List.of());

        assertThrows(AppException.class, () -> service.upsert("owner-1", req));

        // Verify cleanup called for the first copied S3 key!
        verify(storageService).deleteS3ObjectDirect("listing_image/owner-1/copied1.jpg");
    }

    @Test
    void rejectsRoomWithoutCodeEvenForDraft() {
        ListingService service = new ListingService(mock(ListingRepository.class), mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        for (String roomCode : new String[] { "", "   " }) {
            AppException ex = assertThrows(AppException.class,
                    () -> service.upsert("owner-1", createDummyRequest(null, null, roomCode)));
            assertEquals(422, ex.getStatusCode().value());
            assertTrue(ex.getMessage().contains("roomDetail.roomCode"));
        }
        AppException missing = assertThrows(AppException.class,
                () -> service.upsert("owner-1", createDummyRequest(null, null, null)));
        assertEquals(422, missing.getStatusCode().value());
    }

    @Test
    void rejectsUpsertWhenListingIsRentedForSaveDraft() {
        var listingRepo = mock(ListingRepository.class);
        var statusService = mock(ListingStatusService.class);
        var storageService = mock(com.hs.storage.service.StorageService.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                mock(StorageObjectRepository.class), storageService,
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                statusService, mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing rentedListing = new com.hs.listing.model.Listing();
        rentedListing.setId("listing-rented-1");
        rentedListing.setOwnerId("owner-1");
        rentedListing.setStatus(com.hs.listing.model.constant.ListingStatus.RENTED);
        rentedListing.setTitle("Phòng trọ cao cấp");
        rentedListing.setDescription("Mô tả phòng");

        when(listingRepo.findByIdAndActiveTrue("listing-rented-1")).thenReturn(java.util.Optional.of(rentedListing));

        var req = createDummyRequest(null, "listing-rented-1");
        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", req));
        assertEquals(com.hs.listing.advice.ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT.getCode(), ex.getCode());
        assertEquals(409, ex.getStatusCode().value());

        // Verify side effects were prevented
        verify(listingRepo, never()).flush();
        verify(listingRepo, never()).save(any());
        verify(statusService, never()).applySubmission(any(), any(), any(), any());
    }

    @Test
    void rejectsUpsertWhenListingIsRentedForSubmitForReview() {
        var listingRepo = mock(ListingRepository.class);
        var statusService = mock(ListingStatusService.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                statusService, mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing rentedListing = new com.hs.listing.model.Listing();
        rentedListing.setId("listing-rented-1");
        rentedListing.setOwnerId("owner-1");
        rentedListing.setStatus(com.hs.listing.model.constant.ListingStatus.RENTED);

        when(listingRepo.findByIdAndActiveTrue("listing-rented-1")).thenReturn(java.util.Optional.of(rentedListing));

        var reviewReq = createDummyRequest(null, "listing-rented-1", "R202", com.hs.listing.model.constant.ListingSubmissionAction.SUBMIT_FOR_REVIEW);

        AppException ex = assertThrows(AppException.class, () -> service.upsert("owner-1", reviewReq));
        assertEquals(com.hs.listing.advice.ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT.getCode(), ex.getCode());
        assertEquals(409, ex.getStatusCode().value());

        verify(listingRepo, never()).flush();
        verify(listingRepo, never()).save(any());
        verify(statusService, never()).applySubmission(any(), any(), any(), any());
    }

    @Test
    void rejectsUpdateByAdminWhenListingIsRented() {
        var listingRepo = mock(ListingRepository.class);
        ListingService service = new ListingService(listingRepo, mock(AddressRepository.class),
                mock(StorageObjectRepository.class), mock(com.hs.storage.service.StorageService.class),
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing rentedListing = new com.hs.listing.model.Listing();
        rentedListing.setId("listing-rented-1");
        rentedListing.setOwnerId("owner-1");
        rentedListing.setStatus(com.hs.listing.model.constant.ListingStatus.RENTED);

        when(listingRepo.findByIdAndActiveTrue("listing-rented-1")).thenReturn(java.util.Optional.of(rentedListing));

        var req = createDummyRequest(null, "listing-rented-1");
        AppException ex = assertThrows(AppException.class, () -> service.updateByAdmin("admin-1", "listing-rented-1", req));
        assertEquals(com.hs.listing.advice.ListingErrorCode.LISTING_HAS_ACTIVE_CONTRACT.getCode(), ex.getCode());
        assertEquals(409, ex.getStatusCode().value());

        verify(listingRepo, never()).flush();
        verify(listingRepo, never()).save(any());
    }

    @Test
    void allowsDuplicatingFromRentedListing() {
        var listingRepo = mock(ListingRepository.class);
        var statusService = mock(ListingStatusService.class);
        var storageService = mock(com.hs.storage.service.StorageService.class);
        var storageObjRepo = mock(StorageObjectRepository.class);
        var addressRepo = mock(AddressRepository.class);

        ListingService service = new ListingService(listingRepo, addressRepo,
                storageObjRepo, storageService,
                mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                statusService, mock(PropertyBranchRepository.class));

        com.hs.listing.model.Listing rentedListing = new com.hs.listing.model.Listing();
        rentedListing.setId("source-rented-1");
        rentedListing.setOwnerId("owner-1");
        rentedListing.setStatus(com.hs.listing.model.constant.ListingStatus.RENTED);
        rentedListing.setTitle("Phòng trọ đã cho thuê");

        com.hs.storage.model.StorageObject dummyObj = new com.hs.storage.model.StorageObject();
        dummyObj.setId("st-dummy");
        dummyObj.setOwnerId("owner-1");
        dummyObj.setStatus(com.hs.storage.model.constant.StorageStatus.READY);
        dummyObj.setActive(true);
        dummyObj.setPurpose(com.hs.storage.model.constant.StoragePurpose.LISTING_IMAGE);
        dummyObj.setContentType("image/jpeg");
        dummyObj.setObjectKey("listings/dummy.jpg");
        when(storageObjRepo.findById("st-dummy")).thenReturn(java.util.Optional.of(dummyObj));

        when(listingRepo.findByIdAndActiveTrue("source-rented-1")).thenReturn(java.util.Optional.of(rentedListing));
        when(listingRepo.save(any())).thenAnswer(inv -> {
            com.hs.listing.model.Listing saved = inv.getArgument(0);
            saved.setId("new-duplicated-id");
            saved.setStatus(com.hs.listing.model.constant.ListingStatus.DRAFT);
            return saved;
        });

        // Request has duplicateSourceListingId = source-rented-1, id = null
        var duplicateReq = createDummyRequest("source-rented-1", null);
        var resp = service.upsert("owner-1", duplicateReq);

        assertNotNull(resp);
        assertEquals("new-duplicated-id", resp.id());
        // Verify source rented listing was NOT modified
        assertEquals(com.hs.listing.model.constant.ListingStatus.RENTED, rentedListing.getStatus());
        verify(listingRepo).save(any());
        verify(statusService).applySubmission(any(), eq(com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT), eq("owner-1"), any());
    }

    private com.hs.listing.dto.request.CreateListingRequest createDummyRequest(String duplicateSourceListingId, String id) {
        return createDummyRequest(duplicateSourceListingId, id, "R202", com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT);
    }

    private com.hs.listing.dto.request.CreateListingRequest createDummyRequest(String duplicateSourceListingId, String id, String roomCode) {
        return createDummyRequest(duplicateSourceListingId, id, roomCode, com.hs.listing.model.constant.ListingSubmissionAction.SAVE_DRAFT);
    }

    private com.hs.listing.dto.request.CreateListingRequest createDummyRequest(String duplicateSourceListingId, String id, String roomCode, com.hs.listing.model.constant.ListingSubmissionAction action) {
        var pricing = new com.hs.listing.dto.request.ListingPricingRequest(
                java.math.BigDecimal.valueOf(4000000), "VND", com.hs.listing.model.constant.PriceUnit.MONTH,
                false, com.hs.listing.model.constant.DepositType.FIXED_AMOUNT,
                java.math.BigDecimal.valueOf(4000000), null,
                com.hs.listing.model.constant.PaymentCycle.MONTHLY, 12, false, null);

        var addressSource = new com.hs.listing.dto.request.ListingAddressSourceRequest(
                com.hs.listing.model.constant.ListingEnums.AddressSourceType.NEW,
                null, new com.hs.listing.dto.request.ListingAddressRequest("P1", "Province", "W1", "Ward", "123 Street", "123 Street, Ward, Province"));

        var roomDetail = new com.hs.listing.dto.request.RoomDetailRequest(
                roomCode, 2, com.hs.listing.model.constant.ListingEnums.RestroomType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.KitchenType.PRIVATE, true,
                com.hs.listing.model.constant.ListingEnums.BalconyType.PRIVATE, false,
                com.hs.listing.model.constant.FurnishingStatus.UNFURNISHED,
                com.hs.listing.model.constant.ListingEnums.AccessType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.AccessHoursType.FLEXIBLE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                com.hs.listing.model.constant.ListingEnums.MeterType.PRIVATE,
                2, 2, com.hs.listing.model.constant.ListingEnums.ParkingPolicy.FREE);

        return new com.hs.listing.dto.request.CreateListingRequest(
                id, duplicateSourceListingId, null, action,
                "Title", "Description of property listing",
                com.hs.listing.model.constant.ListingCategory.ROOM,
                java.time.LocalDate.now(), java.math.BigDecimal.valueOf(25),
                null, null,
                pricing, null, null, null, null, roomDetail, null, null, null, null,
                addressSource, java.util.List.of(
                        new com.hs.listing.dto.request.ListingMediaRequest("st-dummy", null, com.hs.listing.model.constant.ListingEnums.MediaType.IMAGE, 0, true)),
                java.util.List.of(), java.util.List.of());
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
