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
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AiListingSearchService service = new AiListingSearchService(jdbc);

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
