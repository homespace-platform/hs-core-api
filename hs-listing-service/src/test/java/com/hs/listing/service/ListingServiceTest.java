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

    private boolean hasDeclaredField(Class<?> clazz, String fieldName) {
        try {
            clazz.getDeclaredField(fieldName);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
