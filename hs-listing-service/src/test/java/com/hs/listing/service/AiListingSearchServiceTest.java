package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingSearchRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiListingSearchServiceTest {
    @Test void petPermissionUsesPolicyValueAndCannotMatchAnotherPoliciesRow() {
        when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(),any(MapSqlParameterSource.class),eq(String.class))).thenReturn(List.of());
        service.search(new AiListingSearchRequest(List.of(new AiListingSearchRequest.Filter("policy.petPolicy.value","eq","ALLOWED")),"newest",5));
        var sql = ArgumentCaptor.forClass(String.class); verify(jdbc).queryForList(sql.capture(),any(MapSqlParameterSource.class),eq(String.class));
        assertTrue(sql.getValue().contains("listing_policy_values")); assertTrue(sql.getValue().contains("t.policy_code = 'petPolicy'"));
        assertFalse(sql.getValue().contains("PETS_ALLOWED"));
    }
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AiListingSearchService service = new AiListingSearchService(jdbc);

    @Test void requestedTenAndConfiguredMaximumAreNotSilentlyClampedToFive() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1000L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn(List.of("id-1"));
        service.search(new AiListingSearchRequest(List.of(),"newest",10));
        var parameters=ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(anyString(),parameters.capture(),eq(String.class));
        assertEquals(10,parameters.getValue().getValue("limit"));
        assertThrows(IllegalArgumentException.class,() -> new AiListingSearchService(jdbc,8).search(
            new AiListingSearchRequest(List.of(),"newest",10)));
        assertThrows(IllegalArgumentException.class,() -> new AiListingSearchService(jdbc,51));
    }

    @Test void configuredSmallPageAlsoBoundsTheUnspecifiedDefault() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1000L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn(List.of("id-1"));
        new AiListingSearchService(jdbc,1).search(new AiListingSearchRequest(List.of(),"newest",null));
        var parameters=ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(anyString(),parameters.capture(),eq(String.class));
        assertEquals(1,parameters.getValue().getValue("limit"));
    }

    @Test void rejectsBulkFetchAndAppliesAllPredicatesBeforeBoundedLimit() {
        assertThrows(IllegalArgumentException.class, () -> service.search(new AiListingSearchRequest(List.of(),"newest",1000)));
        verifyNoInteractions(jdbc);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1000L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn(List.of("id-1"));
        service.search(new AiListingSearchRequest(List.of(
            new AiListingSearchRequest.Filter("city_id","eq","12"),
            new AiListingSearchRequest.Filter("ward_id","eq","24588"),
            new AiListingSearchRequest.Filter("category","eq","ROOM"),
            new AiListingSearchRequest.Filter("price_amount","lte",5000000),
            new AiListingSearchRequest.Filter("area_m2","gte",20)),"price_asc",5));
        var sql=ArgumentCaptor.forClass(String.class);
        var params=ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(sql.capture(),params.capture(),eq(String.class));
        assertTrue(sql.getValue().startsWith("SELECT l.id FROM listings l WHERE "));
        assertTrue(sql.getValue().contains("t.ward_id"));
        assertTrue(sql.getValue().contains("l.price_amount <= :v3"));
        assertTrue(sql.getValue().endsWith("ORDER BY l.price_amount ASC, l.id ASC LIMIT :limit"));
        assertEquals(5,params.getValue().getValue("limit"));
    }

    @Test
    void retainsFalseBooleanAndRequiresPublishedListings() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn(List.of("id-1"));
        var request = new AiListingSearchRequest(List.of(
                new AiListingSearchRequest.Filter("category", "eq", "HOUSE"),
                new AiListingSearchRequest.Filter("house.has_garage", "eq", false)), "newest", 5);
        var response = service.search(request);
        assertEquals(List.of("id-1"), response.listingIds());
        var query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(query.capture(), any(MapSqlParameterSource.class), eq(String.class));
        assertTrue(query.getValue().contains("l.status = 'PUBLISHED'"));
        assertTrue(query.getValue().contains("listing_house_details"));
        var parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(anyString(), parameters.capture(), eq(Long.class));
        assertEquals(false, parameters.getValue().getValue("v1"));
    }

    @Test
    void missingAmenityUsesNotExistsRatherThanFalseColumnComparison() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn(List.of());
        service.search(new AiListingSearchRequest(List.of(
                new AiListingSearchRequest.Filter("amenity.WIFI", "eq", false)), "newest", 5));
        var query = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(query.capture(), any(MapSqlParameterSource.class), eq(String.class));
        assertTrue(query.getValue().contains("NOT EXISTS"));
        assertTrue(query.getValue().contains("a.code = 'WIFI'"));
    }

    @Test
    void rejectsUnknownFieldAndOperatorInsteadOfSilentlyIgnoringThem() {
        assertThrows(IllegalArgumentException.class, () -> service.search(new AiListingSearchRequest(
                List.of(new AiListingSearchRequest.Filter("drop_table", "eq", "x")), "newest", 5)));
        assertThrows(IllegalArgumentException.class, () -> service.search(new AiListingSearchRequest(
                List.of(new AiListingSearchRequest.Filter("price_amount", "sql", "x")), "newest", 5)));
        verifyNoInteractions(jdbc);
    }

    @Test
    void publishesFieldsForAllSeedCategoriesAndLinkedData() {
        var fields = service.fields();
        for (String name : List.of("house.has_garage", "apartment.balcony_direction",
                "room.parking_policy", "charge.MOTORBIKE_PARKING.amount", "amenity.WIFI",
                "furnishing.BED.quantity", "custom_amenity.name", "viewing.viewing_slot")) {
            assertTrue(fields.containsKey(name), name);
        }
    }
}
