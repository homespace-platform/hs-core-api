package com.hs.listing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.model.Listing;
import com.hs.listing.model.ListingFurnishingAsset;
import com.hs.listing.model.constant.HandoverCondition;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.*;
import com.hs.storage.config.StorageProperties;
import com.hs.listing.repository.ListingAddressRepository;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ListingFurnishingMappingTest {
    @ParameterizedTest
    @EnumSource(value = ListingCategory.class, names = {"HOUSE", "APARTMENT", "ROOM"})
    void detailAndRentalSnapshotKeepInventoryForEveryResidentialCategory(ListingCategory category) {
        var bed = asset("BED", "Giường", 2, 10);
        var desk = asset("WORK_DESK", "Bàn làm việc", 1, 20);
        var listing = Listing.builder().id("listing").ownerId("owner").category(category)
                .furnishings(List.of(desk, bed)).build();
        var query = new ListingQueryService(mock(ListingRepository.class), mock(ListingAddressRepository.class),
                mock(UserRepository.class), mock(StorageProperties.class), mock(RentalRequestRepository.class),
                mock(PropertyBranchRepository.class));
        var detail = query.toDetail(listing);
        assertEquals(2, detail.furnishings().size());
        assertEquals("BED", detail.furnishings().getFirst().itemCode());
        assertEquals(1, detail.furnishings().getFirst().index());
        assertEquals(2, detail.furnishings().getFirst().quantity());
        assertEquals("Còn tốt — Xác nhận khi bàn giao", detail.furnishings().getFirst().conditionText());

        var requests = new RentalRequestService(mock(RentalRequestRepository.class), mock(ListingRepository.class),
                mock(ListingStatusService.class), mock(ListingAddressRepository.class), mock(StorageProperties.class),
                mock(RentalCostCalculator.class), mock(ParkingReservationService.class),
                mock(PropertyBranchRepository.class), new ObjectMapper(), 60);
        var snapshot = requests.buildListingSnapshotDto(listing);
        assertEquals(category, snapshot.getCategory());
        assertEquals(2, snapshot.getFurnishings().size());
        assertEquals("Giường", snapshot.getFurnishings().getFirst().getAssetName());
        assertEquals("GOOD", snapshot.getFurnishings().getFirst().getHandoverCondition());
        bed.setAssetName("Tên bị sửa sau khi chốt");
        assertEquals("Giường", snapshot.getFurnishings().getFirst().getAssetName());
        listing.setFurnishings(List.of());
        assertTrue(query.toDetail(listing).furnishings().isEmpty());
        assertTrue(requests.buildListingSnapshotDto(listing).getFurnishings().isEmpty());
    }

    private ListingFurnishingAsset asset(String code, String name, int quantity, int order) {
        var asset = new ListingFurnishingAsset();
        asset.setItemCode(code); asset.setAssetName(name); asset.setQuantity(quantity);
        asset.setSortOrder(order); asset.setHandoverCondition(HandoverCondition.GOOD);
        asset.setConditionNote("Xác nhận khi bàn giao");
        return asset;
    }
}
