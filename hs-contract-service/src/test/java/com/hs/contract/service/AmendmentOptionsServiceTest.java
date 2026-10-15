package com.hs.contract.service;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.Contract;
import com.hs.listing.dto.response.ListingOptionsResponse;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.service.ListingOptionsService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AmendmentOptionsServiceTest {
    @Test void categoriesUseActualListingInventoryAndSharedCustomLabelsWithoutInventedQuantity() {
        for (var category : List.of(ListingCategory.HOUSE,ListingCategory.APARTMENT,ListingCategory.ROOM)) {
            var repository = mock(ListingRepository.class); var catalogs = mock(ListingOptionsService.class);
            var ac = new Amenity(); ac.setCode("AIR_CONDITIONER"); ac.setName("Máy lạnh");
            var wifi = new Amenity(); wifi.setCode("WIFI"); wifi.setName("WiFi");
            var dry = new ListingCustomAmenity(); dry.setName("Sân phơi chung");
            var unknown = new ListingCustomAmenity(); unknown.setName("Vườn cây");
            var asset = new ListingFurnishingAsset(); asset.setAssetName("Máy lạnh"); asset.setQuantity(3);
            asset.setHandoverCondition(com.hs.listing.model.constant.HandoverCondition.GOOD); asset.setConditionNote("Dấu xước bên trái");
            var listing = Listing.builder().id("listing").category(category).amenities(Set.of(ac,wifi))
                    .customAmenities(List.of(dry,unknown)).furnishings(List.of(asset))
                    .branch(PropertyBranch.builder().buildingAmenities(Set.of(wifi)).build()).build();
            when(repository.findById("listing")).thenReturn(Optional.of(listing));
            when(catalogs.getOptions(category)).thenReturn(new ListingOptionsResponse(category,List.of(),List.of()));
            var service = new AmendmentOptionsService(repository,catalogs); var contract = Contract.builder().listingId("listing").build();
            var current = Map.<String,Object>of("amenities",List.of()); var result = service.get(contract,current);
            assertEquals(3,result.equipments().getFirst().get("quantity")); assertEquals("Dấu xước bên trái",result.equipments().getFirst().get("note"));
            assertEquals(1,result.equipments().size()); assertEquals(4,result.amenities().size());
            assertTrue(result.amenities().stream().anyMatch(r -> "AIR_CONDITIONER".equals(r.get("code"))));
            for (var row : result.amenities()) assertEquals(Set.of("code","name","sourceType"),row.keySet());
            assertFalse(result.billingMethodsByChargeType().get("INTERNET").contains("PER_VEHICLE_MONTH"));
            listing.setFurnishings(List.of()); assertTrue(service.get(contract,current).equipments().isEmpty());
        }
    }
    @Test void candidatesComeFromContractListingAndSharedPropertyNeverGlobalAmenities() {
        var repository = mock(ListingRepository.class); var catalogs = mock(ListingOptionsService.class);
        Amenity wifi = new Amenity(); wifi.setCode("WIFI"); wifi.setName("WiFi");
        Amenity pool = new Amenity(); pool.setCode("POOL"); pool.setName("Hồ bơi");
        var branch = PropertyBranch.builder().buildingAmenities(Set.of(pool)).build();
        var listing = Listing.builder().id("listing").category(ListingCategory.APARTMENT).branch(branch).amenities(Set.of(wifi)).build();
        when(repository.findById("listing")).thenReturn(Optional.of(listing));
        when(catalogs.getOptions(ListingCategory.APARTMENT)).thenReturn(new ListingOptionsResponse(ListingCategory.APARTMENT,List.of(),List.of()));
        var service = new AmendmentOptionsService(repository,catalogs); var contract = Contract.builder().listingId("listing").build();
        var current = Map.<String,Object>of("amenities",List.of());
        var options = service.get(contract,current); assertEquals(1,options.amenities().size()); assertEquals("APARTMENT",options.category());
        var shared = new LinkedHashMap<>(options.amenities().getFirst()); shared.put("scope","Riêng trong căn/phòng/nhà");
        assertThrows(AppException.class,() -> service.validate(contract,current,Map.of("amenities",List.of(shared))));
        assertThrows(AppException.class,() -> service.validate(contract,current,Map.of("amenities",List.of(Map.of("code","FORGED","name","Tiện ích tài sản khác")))));
        var declared = options.amenities().stream().map(LinkedHashMap::new).toList();
        service.validate(contract,current,Map.of("amenities",declared));
        var forgedCost = new LinkedHashMap<>(options.amenities().getFirst()); forgedCost.put("costText","100.000đ mỗi ngày");
        assertThrows(AppException.class,() -> service.validate(contract,current,Map.of("amenities",List.of(forgedCost))));
        service.validate(contract,Map.of("amenities",List.of(Map.of("code","OLD","name","Quyền đã ký"))),Map.of("amenities",List.of(Map.of("code","OLD","name","Quyền đã ký"))));
    }
}
