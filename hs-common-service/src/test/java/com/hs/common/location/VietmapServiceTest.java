package com.hs.common.location;

import com.fasterxml.jackson.databind.*;
import com.hs.common.advice.entity.AppException;
import com.hs.common.persistence.AddressFields;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VietmapServiceTest {
    static class Address extends AddressFields {}
    static final ObjectMapper JSON=new ObjectMapper();
    static JsonNode place() throws Exception {
        try(var in=VietmapServiceTest.class.getResourceAsStream("/vietmap/place-v4.json")) { return JSON.readTree(in); }
    }
    static JsonNode dualFormat() throws Exception {
        try(var in=VietmapServiceTest.class.getResourceAsStream("/vietmap/dual-format-autocomplete-v4.json")) { return JSON.readTree(in); }
    }
    @Test void mapsActualPlaceResponseWithoutConfusingAdministrativeAddressWithStreet() throws Exception {
        VietmapService service=new VietmapService((path,params) -> {
            assertEquals("/api/place/v4",path);assertEquals("geocode:test",params.get("refid"));return place();
        });
        Address a=new Address(); service.apply(a,"geocode:test","Căn 401");
        assertEquals("197",a.getHouseNumber());assertEquals("Trần Phú",a.getStreet());
        assertEquals("Phường Chợ Quán, Thành phố Hồ Chí Minh",a.getAddress());
        assertEquals("12",a.getCityId());assertEquals("18700",a.getWardId());
        assertEquals("197 Trần Phú, Phường Chợ Quán, Thành phố Hồ Chí Minh",a.getDisplay());
        assertEquals(new BigDecimal("10.759222947000069"),a.getLatitude());
        assertNull(a.getDistrictId());assertNull(a.getDistrict());assertEquals("Căn 401",a.getAddressNote());assertNotNull(a.getResolvedAt());
    }
    @Test void upstreamFailureDoesNotMutateTheExistingSavedAddressOrLeakSecrets() {
        var service=new VietmapService((path,params) -> {throw new RuntimeException("apikey=SECRET");});
        Address a=new Address();a.setDisplay("Địa chỉ đã lưu");a.setRefId("old");
        var ex=assertThrows(AppException.class,() -> service.apply(a,"geocode:test",null));
        assertEquals(502,ex.getStatusCode().value());assertFalse(ex.getMessage().contains("SECRET"));assertNull(ex.getCause());
        assertEquals("Địa chỉ đã lưu",a.getDisplay());assertEquals("old",a.getRefId());
    }
    @Test void acceptsActualConcreteAddressWithUnparsedStreetAndPreservesProviderValues() throws Exception {
        JsonNode response;
        try(var in=getClass().getResourceAsStream("/vietmap/place-v4-unparsed-street.json")) { response=JSON.readTree(in); }
        var service=new VietmapService((path,params) -> response);
        Address a=new Address();service.apply(a,"vm:concrete-unparsed",null);
        assertEquals("194 Đình Phong Phú",a.getName());assertEquals("",a.getStreet());assertNull(a.getHouseNumber());
        assertEquals("4620",a.getWardId());assertEquals("Phường Tăng Nhơn Phú",a.getWard());
        assertEquals(new BigDecimal("10.8344664"),a.getLatitude());assertNotNull(a.getResolvedAt());
        assertEquals(response.path("display").asText(),a.getDisplay());
    }
    @Test void stillRejectsAdministrativeOnlyOrUnnamedResultsWithoutAStreet() throws Exception {
        for(String name:List.of("","Phường Chợ Quán","Thành phố Hồ Chí Minh","Phường Chợ Quán, Thành phố Hồ Chí Minh")) {
            var response=(com.fasterxml.jackson.databind.node.ObjectNode)place();
            response.put("street","");response.put("name",name);
            var service=new VietmapService((path,params) -> response);
            assertEquals(400,assertThrows(AppException.class,() -> service.place("vm:area-only")).getStatusCode().value());
        }
    }
    @Test void autocompleteAndGeocodeUseNewPrimaryFormatAndReturnOnlySelectedMetadata() throws Exception {
        var paths=new ArrayList<String>();
        var service=new VietmapService((path,params) -> { paths.add(path);assertEquals("1",params.get("display_type"));
            return JSON.readTree("[{\"ref_id\":\"auto:opaque\",\"display\":\"Địa chỉ mới\",\"data_old\":{\"display\":\"Địa chỉ cũ\"}}]"); });
        assertEquals("Địa chỉ mới",service.autocomplete("197 Trần Phú").getFirst().display());
        assertEquals("auto:opaque",service.geocode("197 Trần Phú").getFirst().refId());
        assertEquals(List.of("/api/autocomplete/v4","/api/search/v4"),paths);
    }
    @Test void actualDualFormatResponseUsesNewRootEvenWhenDataNewIsNullForAllSearchEndpoints() throws Exception {
        JsonNode response=dualFormat();
        var paths=new ArrayList<String>();
        var service=new VietmapService((path,params) -> {
            paths.add(path);assertEquals("1",params.get("display_type"));return response;
        });
        var results=List.of(service.autocomplete("Trần Bá Giao"),service.geocode("Trần Bá Giao"),
                service.reverse(BigDecimal.TEN,BigDecimal.valueOf(106)));
        for(var result:results) {
            assertEquals(2,result.size());
            for(int i=0;i<result.size();i++) {
                assertEquals(response.get(i).path("ref_id").asText(),result.get(i).refId());
                assertEquals("Phường An Nhơn, Thành phố Hồ Chí Minh",result.get(i).address());
                assertNotEquals(response.get(i).path("data_old").path("ref_id").asText(),result.get(i).refId());
                assertFalse(result.get(i).display().contains("Phường 5"));
            }
            // Gò Vấp remains part of the business's proper name, not an administrative district.
            assertEquals(response.get(0).path("name").asText(),result.getFirst().name());
            assertTrue(result.getFirst().name().contains("Gò Vấp"));
        }
        assertEquals(List.of("/api/autocomplete/v4","/api/search/v4","/api/reverse/v4"),paths);
    }
    @Test void oldPrimaryWithDataNewUsesOnlyTheNewBranchIncludingItsOpaqueRefForPlace() throws Exception {
        var fresh=dualFormat().get(1).deepCopy();
        var old=(com.fasterxml.jackson.databind.node.ObjectNode)fresh.path("data_old").deepCopy();
        old.set("data_new",fresh);
        var service=new VietmapService((path,params) -> {
            if(path.equals("/api/place/v4")) {
                assertEquals(fresh.path("ref_id").asText(),params.get("refid"));return place();
            }
            return JSON.createArrayNode().add(old);
        });
        var selected=service.autocomplete("Trần Bá Giao").getFirst();
        assertEquals(fresh.path("ref_id").asText(),selected.refId());
        assertEquals(fresh.path("display").asText(),selected.display());
        assertEquals(fresh.path("address").asText(),selected.address());
        assertEquals(selected.refId(),service.place(selected.refId()).refId());
    }
    @Test void neverReturnsOldAdministrativeSuggestionsOrFallsBackToDataOld() throws Exception {
        var old=(com.fasterxml.jackson.databind.node.ObjectNode)dualFormat().get(0).path("data_old").deepCopy();
        // Even a seemingly usable nested data_old must not become a fallback selection.
        old.set("data_old",dualFormat().get(0));
        var service=new VietmapService((path,params) -> JSON.createArrayNode().add(old));
        assertTrue(service.autocomplete("Trần Bá Giao").isEmpty());
        assertTrue(service.geocode("Trần Bá Giao").isEmpty());
        assertTrue(service.reverse(BigDecimal.TEN,BigDecimal.valueOf(106)).isEmpty());
    }
    @Test void malformedNewBranchDoesNotFallBackToPrimaryOrOldAddress() throws Exception {
        for(String branch:List.of("{}","[]","\"invalid\"")) {
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)dualFormat().get(0).deepCopy();
            root.set("data_new",JSON.readTree(branch));
            var service=new VietmapService((path,params) -> JSON.createArrayNode().add(root));
            assertThrows(AppException.class,() -> service.autocomplete("Trần Bá Giao"));
        }
    }
    @Test void oldPlaceReferenceIsRejectedBeforeAnyAddressIsMutated() throws Exception {
        for(boolean oldId:List.of(true,false)) {
            var response=(com.fasterxml.jackson.databind.node.ObjectNode)place();
            if(oldId) response.put("district_id",3852);else response.put("district","Quận Gò Vấp");
            var service=new VietmapService((path,params) -> response);
            Address existing=new Address();existing.setRefId("saved:new");existing.setDisplay("Địa chỉ đã lưu");
            var ex=assertThrows(AppException.class,() -> service.apply(existing,"old:ref",null));
            assertEquals(400,ex.getStatusCode().value());assertTrue(ex.getMessage().contains("sau sáp nhập"));
            assertEquals("saved:new",existing.getRefId());assertEquals("Địa chỉ đã lưu",existing.getDisplay());
            assertNull(existing.getResolvedAt());
        }
    }
    @Test void reverseReturnsSuggestionsAndRequiresValidCoordinatesBeforeCallingProvider() throws Exception {
        var calls=new AtomicInteger();
        var service=new VietmapService((path,params) -> {calls.incrementAndGet();assertEquals("/api/reverse/v4",path);return JSON.readTree("[]");});
        assertThrows(AppException.class,() -> service.reverse(new BigDecimal("91"),BigDecimal.ZERO));
        assertThrows(AppException.class,() -> service.reverse(BigDecimal.ZERO,new BigDecimal("181")));
        assertThrows(AppException.class,() -> service.reverse(null,BigDecimal.ZERO));assertEquals(0,calls.get());
        assertTrue(service.reverse(BigDecimal.TEN,BigDecimal.valueOf(106)).isEmpty());assertEquals(1,calls.get());
    }
    @Test void rejectsMissingOrWrongShapeAndIncompletePlaceRatherThanFabricatingCoordinates() {
        for(String body:List.of("null","[]","{}","{\"lat\":null,\"lng\":106}")) {
            var service=new VietmapService((path,params) -> JSON.readTree(body));
            assertThrows(AppException.class,() -> service.place("vm:opaque"));
        }
    }
    @Test void rejectsOverlongControlCharacterOrMissingInputWithoutNetwork() {
        var service=new VietmapService((path,params) -> {fail("Must validate before network");return null;});
        assertThrows(AppException.class,() -> service.autocomplete("x"));
        assertThrows(AppException.class,() -> service.geocode("x".repeat(256)));
        assertThrows(AppException.class,() -> service.place(""));
        assertThrows(AppException.class,() -> service.place("x".repeat(4097)));
        assertThrows(AppException.class,() -> service.place("ref\nsecret"));
    }
    @Test void missingApiKeyFailsClosedWithConfigurationMessage() {
        var service=new VietmapService("");
        assertEquals(503,assertThrows(AppException.class,() -> service.place("vm:test")).getStatusCode().value());
    }
    @Test void copiesOnlyAddressValuesAndNotIdentityOrVersion() throws Exception {
        Address original=new Address(),copy=new Address();original.setId("user-address");original.setVersion(9L);
        new VietmapService((path,params) -> place()).apply(original,"vm:test",null);
        copy.setId("listing-address");copy.copyAddressFrom(original);original.setDisplay("Changed profile");
        assertEquals("listing-address",copy.getId());assertNull(copy.getVersion());assertNotEquals(original.getDisplay(),copy.getDisplay());
        assertEquals(original.getLatitude(),copy.getLatitude());assertEquals(original.getRefId(),copy.getRefId());
    }
}
