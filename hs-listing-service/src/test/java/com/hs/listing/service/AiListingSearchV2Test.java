package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.dto.request.AiListingSearchRequest.Condition;
import com.hs.listing.dto.request.AiListingSearchRequest.SearchContext;
import com.hs.listing.nearby.NearbyQueryService;
import com.hs.listing.nearby.NearbyTypes.Group;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiListingSearchV2Test {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AiListingSearchService service = new AiListingSearchService(jdbc);

    @BeforeEach void counts() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class))).thenReturn(List.of());
    }

    private static AiListingSearchRequest v2(List<Condition> all, SearchContext context, String sort) {
        return new AiListingSearchRequest(null, sort, 5, null, null, 2, all == null ? null : Condition.allOf(all), null, context,
                null, null, false);
    }

    private String sql() {
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(MapSqlParameterSource.class), eq(String.class));
        return sql.getValue();
    }

    private MapSqlParameterSource params() {
        var params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForList(anyString(), params.capture(), eq(String.class));
        return params.getValue();
    }

    @Test void integerPoliciesAndEveryFurnishingCodeArePublishedWithMetadata() {
        var fields = service.fields();
        for (String code : List.of("policy.depositRefundDays.value", "policy.noticeDaysBeforeTermination.value",
                "policy.propertyInspectionNotice.value", "furnishing.RANGE_HOOD", "furnishing.MICROWAVE",
                "furnishing.SHOE_CABINET", "furnishing.WATER_TANK_PUMP.handover_condition",
                "furnishing.BED.total_quantity", "rent.monthly_amount", "cost.fixed_monthly_total",
                "occupancy.max_occupants", "deposit.amount_estimate")) {
            assertTrue(fields.containsKey(code), code);
        }
        assertEquals("INTEGER", fields.get("policy.depositRefundDays.value"));
        var view = service.catalog();
        assertEquals(2, view.version());
        assertTrue(view.catalogVersion().startsWith("fields-v2-"));
        var pet = view.fields().stream().filter(f -> f.code().equals("policy.petPolicy.value")).findFirst().orElseThrow();
        assertEquals(List.of("ALLOWED", "NOT_ALLOWED", "APPROVAL_REQUIRED"), pet.values().stream().map(AiListingFieldCatalog.Choice::code).toList());
        var refund = view.fields().stream().filter(f -> f.code().equals("policy.depositRefundDays.value")).findFirst().orElseThrow();
        assertEquals("ngày", refund.unit());
        assertTrue(view.unsupported().stream().anyMatch(u -> u.code().equals("QUIETNESS")));
        assertTrue(view.fields().stream().anyMatch(f -> f.role() == AiListingFieldCatalog.Role.DISPLAY_ONLY));
    }

    @Test void newReferenceItemBecomesSearchableWithoutCodeChangeAndChangesTheVersion() {
        var seeded = AiListingFieldCatalog.seedReference();
        var amenities = new ArrayList<>(seeded.amenities());
        amenities.add(new AiListingFieldCatalog.RefItem("ROOFTOP_GARDEN", "Vườn sân thượng", List.of("HOUSE")));
        var extended = AiListingFieldCatalog.of(new AiListingFieldCatalog.Reference(amenities, seeded.furnishings(), seeded.policies()));
        assertTrue(extended.legacyFields().containsKey("amenity.ROOFTOP_GARDEN"));
        assertNotEquals(AiListingFieldCatalog.seeded().version(), extended.version());
        assertThrows(IllegalStateException.class, () -> AiListingFieldCatalog.of(new AiListingFieldCatalog.Reference(
                List.of(new AiListingFieldCatalog.RefItem("X'; DROP", "bad", List.of())), List.of(), List.of())));
    }

    @Test void liveCatalogRequiresAConfiguredCacheWindow() {
        assertThrows(IllegalStateException.class, () -> new AiListingFieldCatalog(jdbc, 0));
        assertThrows(IllegalStateException.class, () -> new AiListingFieldCatalog(jdbc, 100000));
    }

    @Test void integerPolicyUsesGuardedCastOnTheSamePolicyRow() {
        service.search(v2(List.of(Condition.leaf("policy.depositRefundDays.value", "lte", "7")), null, null));
        String sql = sql();
        assertTrue(sql.contains("t.policy_code = 'depositRefundDays'"));
        assertTrue(sql.contains("CASE WHEN t.policy_value ~ '^-?[0-9]{1,9}$' THEN CAST(t.policy_value AS integer) END <= :v0"));
        assertEquals(7, params().getValue("v0"));
    }

    @Test void invalidEnumIsAStructuredErrorNotAnEmptyResult() {
        var error = assertThrows(AiSearchValidationException.class, () -> service.search(
                v2(List.of(Condition.leaf("policy.petPolicy.value", "eq", "YES")), null, null)));
        assertEquals("INVALID_VALUE", error.reason());
        assertEquals("policy.petPolicy.value", error.field());
        assertTrue(error.allowed().contains("APPROVAL_REQUIRED"));
        var unknown = assertThrows(AiSearchValidationException.class, () -> service.search(
                v2(List.of(Condition.leaf("quietness", "eq", "true")), null, null)));
        assertEquals("UNKNOWN_FIELD", unknown.reason());
        var display = assertThrows(AiSearchValidationException.class, () -> service.search(
                v2(List.of(Condition.leaf("display.highlights", "contains", "x")), null, null)));
        assertEquals("NOT_FILTERABLE", display.reason());
        verifyNoInteractions(jdbc);
    }

    @Test void conditionsOnOneChargeTypeAreEvaluatedOnTheSameRow() {
        service.search(v2(List.of(
                Condition.leaf("charge.ELECTRICITY.billing_method", "eq", "PER_KWH"),
                Condition.leaf("charge.ELECTRICITY.amount", "lte", "3500"),
                Condition.leaf("charge.WATER.amount", "lte", "100000")), null, null));
        String sql = sql();
        assertEquals(2, count(sql, "FROM listing_charges t WHERE"));
        assertTrue(sql.contains("t.charge_type = 'ELECTRICITY' AND t.billing_method = :v0 AND t.amount <= :v1"));
    }

    @Test void furnishingQuantityAndConditionShareRowsAndUseOrdinalRanks() {
        service.search(v2(List.of(
                Condition.leaf("furnishing.BED.handover_condition", "gte", "GOOD"),
                Condition.leaf("furnishing.BED.total_quantity", "gte", "2")), null, null));
        String sql = sql();
        assertTrue(sql.contains("(SELECT coalesce(sum(t.quantity), 0) FROM listing_furnishing_assets t WHERE t.listing_id = l.id"
                + " AND t.item_code = 'BED' AND t.handover_condition IN (:v0)) >= :v1"), sql);
        assertEquals(List.of("BRAND_NEW", "GOOD"), params().getValue("v0"));
        assertEquals(2, params().getValue("v1"));
    }

    @Test void orGroupsKeepTheirOwnConditionsAndAreCombinedWithAnd() {
        service.search(v2(List.of(
                Condition.leaf("ward_id", "eq", "24588"),
                Condition.anyOf(List.of(
                        Condition.allOf(List.of(Condition.leaf("category", "eq", "ROOM"), Condition.leaf("rent.monthly_amount", "lte", "3000000"))),
                        Condition.allOf(List.of(Condition.leaf("category", "eq", "HOUSE"), Condition.leaf("rent.monthly_amount", "lte", "8000000")))))),
                new SearchContext(2, null, null, null, null), null));
        String sql = sql();
        assertTrue(sql.contains(" OR "));
        assertTrue(sql.contains("l.price_unit = 'PERSON_MONTH' THEN l.price_amount * CAST(:ctxOccupants AS integer)"));
        assertEquals(2, params().getValue("ctxOccupants"));
        assertEquals("ROOM", params().getValue("v1"));
        assertEquals("HOUSE", params().getValue("v3"));
    }

    @Test void perPersonRentWithoutOccupantsIsUnknownAndReported() {
        var response = service.search(v2(List.of(Condition.leaf("rent.monthly_amount", "lte", "3000000")), null, "rent_asc"));
        assertEquals(List.of("occupants"), response.contextMissing());
        assertNull(params().getValue("ctxOccupants"));
        var counts = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).queryForObject(counts.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        assertTrue(counts.getAllValues().get(1).endsWith(") IS NULL"));
        assertTrue(sql().contains("ASC NULLS LAST, l.id ASC LIMIT :limit"));
    }

    @Test void costTotalsMirrorTheEstimateRulesAndRejectInvalidContext() {
        service.search(v2(List.of(Condition.leaf("cost.fixed_monthly_total", "lte", "4500000")),
                new SearchContext(2, 1, 0, null, null), null));
        String sql = sql();
        assertTrue(sql.contains("WHEN t.charge_type = 'MANAGEMENT' AND l.category IN ('ROOM','HOUSE') THEN 0"));
        assertTrue(sql.contains("WHEN t.included_in_rent OR t.billing_method IN ('INCLUDED','FREE','NOT_APPLICABLE') THEN 0"));
        assertTrue(sql.contains("bool_or(x.v IS NULL) THEN NULL"));
        var error = assertThrows(AiSearchValidationException.class, () -> service.search(v2(
                List.of(Condition.leaf("cost.fixed_monthly_total", "lte", "1")), new SearchContext(0, null, null, null, null), null)));
        assertEquals("INVALID_CONTEXT", error.reason());
    }

    @Test void scopeAndExclusionStayInsideThePublicPredicate() {
        String id = "00000000-0000-0000-0000-000000000001";
        service.search(new AiListingSearchRequest(null, "newest", 5, null, null, 2, null, null, null, List.of(id), List.of(id.replace('1', '2')), false));
        String sql = sql();
        assertTrue(sql.contains("l.status = 'PUBLISHED'"));
        assertTrue(sql.contains("l.id IN (:scopeIds)") && sql.contains("l.id NOT IN (:excludeIds)"));
        assertThrows(AiSearchValidationException.class, () -> service.search(new AiListingSearchRequest(null, "newest", 5, null, null, 2,
                null, null, null, List.of("not-a-uuid"), null, false)));
        assertThrows(AiSearchValidationException.class, () -> service.search(new AiListingSearchRequest(null, "newest", 5, null, null, 2,
                null, null, null, List.of(), null, false)));
    }

    @Test void treeDepthAndSizeAreBounded() {
        Condition deep = Condition.leaf("category", "eq", "ROOM");
        for (int i = 0; i < 6; i++) deep = Condition.anyOf(List.of(deep));
        Condition tooDeep = deep;
        assertEquals("TOO_COMPLEX", assertThrows(AiSearchValidationException.class,
                () -> service.search(v2(List.of(tooDeep), null, null))).reason());
        List<Condition> many = new ArrayList<>();
        for (int i = 0; i < 41; i++) many.add(Condition.leaf("area_m2", "gte", "1"));
        assertEquals("TOO_COMPLEX", assertThrows(AiSearchValidationException.class,
                () -> service.search(v2(many, null, null))).reason());
    }

    @Test void namedTargetUsesTheBoundTargetDistanceAndCanSortByIt() {
        NearbyQueryService nearby = mock(NearbyQueryService.class);
        service.setNearbyQuery(nearby);
        String place = "00000000-0000-0000-0000-00000000000a";
        when(nearby.requireTarget(place)).thenReturn(new NearbyQueryService.TargetPoint(place, "IUH", 10.82, 106.68));
        when(nearby.targetPredicate(any(), eq(2000), any(), eq("N0"))).thenReturn("TARGET_DISTANCE<=:targetRadiusN0");
        service.search(new AiListingSearchRequest(null, "target_distance", 5, null, null, 2, null,
                List.of(new AiListingSearchRequest.NearbyCriteria(place, null, 2000)), null, null, null, false));
        String sql = sql();
        assertTrue(sql.contains("(TARGET_DISTANCE<=:targetRadiusN0)"));
        assertFalse(sql.contains("listing_nearby_places"));
        assertTrue(sql.contains("ORDER BY (SELECT 2*6371008.8*asin("));
        verify(nearby, times(1)).requireTarget(place);
        when(nearby.requireTarget("00000000-0000-0000-0000-00000000000b")).thenThrow(new IllegalArgumentException("Target place is unavailable"));
        var error = assertThrows(AiSearchValidationException.class, () -> service.search(new AiListingSearchRequest(null, "newest", 5, null,
                new AiListingSearchRequest.NearbyCriteria("00000000-0000-0000-0000-00000000000b", null, 2000))));
        assertEquals("TARGET_UNAVAILABLE", error.reason());
    }

    @Test void severalNearbyPredicatesAreAllApplied() {
        NearbyQueryService nearby = mock(NearbyQueryService.class);
        service.setNearbyQuery(nearby);
        when(nearby.groupPredicate(eq(null), eq("SUPERMARKET"), eq(1000), any(), eq("N0"))).thenReturn("SUPERMARKET_NEAR");
        when(nearby.groupPredicate(eq(Group.HEALTHCARE), eq(null), eq(1000), any(), eq("N1"))).thenReturn("HEALTH_NEAR");
        service.search(new AiListingSearchRequest(null, "newest", 5, null, null, 2, null, List.of(
                new AiListingSearchRequest.NearbyCriteria(null, null, 1000, "SUPERMARKET"),
                new AiListingSearchRequest.NearbyCriteria(null, Group.HEALTHCARE, 1000)), null, null, null, false));
        String sql = sql();
        assertTrue(sql.contains("(SUPERMARKET_NEAR) AND (HEALTH_NEAR)"));
    }

    @Test @SuppressWarnings("unchecked") void evidenceReportsEveryConditionForReturnedListings() {
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(String.class)))
                .thenReturn(List.of("00000000-0000-0000-0000-000000000001"));
        service.search(new AiListingSearchRequest(null, "newest", 5, null, null, 2, Condition.allOf(List.of(
                Condition.leaf("category", "eq", "ROOM"), Condition.leaf("rent.monthly_amount", "lte", "3000000"))),
                null, null, null, null, true));
        var sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
        assertTrue(sql.getValue().contains("THEN 'MATCH' WHEN NOT ("));
        assertTrue(sql.getValue().contains(" AS a1"));
        assertTrue(sql.getValue().endsWith("WHERE l.id IN (:evidenceIds)"));
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int index = text.indexOf(part); index >= 0; index = text.indexOf(part, index + 1)) count++;
        return count;
    }
}
