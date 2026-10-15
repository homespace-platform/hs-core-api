package com.hs.listing.service;

import com.hs.listing.model.*;
import com.hs.listing.model.constant.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ListingAmenitySnapshotsTest {
    @Test void listingNamesAreCopiedWithoutScopeGuessesOrBranchOnlyAmenities() {
        var wifi = new Amenity(); wifi.setCode("WIFI"); wifi.setName("WiFi");
        var drying = new ListingCustomAmenity(); drying.setName("Sân phơi chung");
        var garden = new ListingCustomAmenity(); garden.setName("Vườn chung cư");
        var listing = Listing.builder().amenities(Set.of(wifi)).customAmenities(List.of(drying,garden)).build();
        var pool = new Amenity(); pool.setCode("POOL"); pool.setName("Hồ bơi chưa chọn trên bài đăng");
        var result = ListingAmenitySnapshots.resolve(listing,PropertyBranch.builder().buildingAmenities(Set.of(wifi,pool)).build());
        assertEquals(List.of("WiFi","Sân phơi chung","Vườn chung cư"),result.stream().map(r -> r.getName()).toList());
        assertEquals("LISTING",result.getFirst().getSourceType());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result).toString().contains("scope"));
    }
    @Test void methodRulesMatchSourceUnitsAndDoNotMixVehiclesWithInternet() {
        assertFalse(ChargeMethodRules.methods("INTERNET").contains("PER_VEHICLE_MONTH"));
        assertFalse(ChargeMethodRules.methods("INTERNET").contains("PER_PERSON_MONTH"));
        assertTrue(ChargeMethodRules.methods("SERVICE_OR_GARBAGE").contains("PER_PERSON_MONTH"));
        assertTrue(ChargeMethodRules.methods("MANAGEMENT").contains("PER_M2_MONTH"));
        assertTrue(ChargeMethodRules.methods("WATER").contains("PER_M3"));
        assertTrue(ChargeMethodRules.methods("MOTORBIKE_PARKING").contains("PER_VEHICLE_MONTH"));
        assertTrue(ChargeMethodRules.methods("UNKNOWN").isEmpty());
    }
}
