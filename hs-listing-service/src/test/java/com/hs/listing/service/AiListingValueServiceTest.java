package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.dto.request.AiListingSearchRequest.Condition;
import com.hs.listing.dto.request.AiListingValueRequest;
import com.hs.listing.dto.response.AiListingSearchResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiListingValueServiceTest {
    private static AiListingSearchRequest request(List<Condition> all) {
        return new AiListingSearchRequest(null, "newest", 4, null, null, 2,
                Condition.allOf(all), null, null, null, null, false);
    }

    @Test void effectiveBudgetUsesMostRestrictiveConjunctiveBoundAndExactInclusivity() {
        var budget = AiListingValueService.budget(request(List.of(
                Condition.leaf("rent.monthly_amount", "lte", "5000000"),
                Condition.leaf("price_amount", "lt", "4500000"),
                Condition.leaf("rent.monthly_amount", "lte", "4500000"))));
        assertEquals(new BigDecimal("4500000"), budget.ceiling());
        assertEquals("rent.monthly_amount", budget.field());
        assertTrue(budget.strict());
        budget = AiListingValueService.budget(request(List.of(Condition.leaf("rent.monthly_amount", "lte", "4500000"))));
        assertFalse(budget.strict());
    }

    @Test void exactPricesLowerBoundsAndAllInCostsAreNotSilentlyRelaxed() {
        assertNull(AiListingValueService.budget(request(List.of(Condition.leaf("rent.monthly_amount", "eq", "4500000")))));
        assertNull(AiListingValueService.budget(request(List.of(Condition.leaf("rent.monthly_amount", "gte", "4500000")))));
        assertNull(AiListingValueService.budget(request(List.of(Condition.leaf("cost.fixed_monthly_total", "lt", "4500000")))));
        assertNull(AiListingValueService.budget(request(List.of(Condition.leaf("rent.monthly_amount", "lt", "NaN")))));
    }

    @Test void budgetInAnOrBranchCannotBeTurnedIntoOneGlobalBoundary() {
        assertNull(AiListingValueService.budget(request(List.of(Condition.anyOf(List.of(
                Condition.leaf("rent.monthly_amount", "lt", "4500000"), Condition.leaf("area_m2", "gte", "30")))))));
        var budget = AiListingValueService.budget(request(List.of(Condition.leaf("rent.monthly_amount", "lt", "4500000"),
                Condition.anyOf(List.of(Condition.leaf("category", "eq", "ROOM"), Condition.leaf("category", "eq", "APARTMENT"))))));
        assertNotNull(budget, "An unrelated category OR does not prevent extracting the global budget");
    }

    @Test void legacyFlatUpperFiltersAreSupportedInsideTheVersionedRequest() {
        var request = new AiListingSearchRequest(List.of(new AiListingSearchRequest.Filter("price_amount", "lt", "4500000")),
                "newest", 4, null, null, 2, null, null, null, null, null, false);
        assertEquals(new BigDecimal("4500000"), AiListingValueService.budget(request).ceiling());
    }

    @Test void emptyBoundaryRetainsBudgetMetadataAndNeverInventsACandidate() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var search = mock(AiListingSearchService.class);
        String id = "00000000-0000-0000-0000-000000000001";
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(1L);
        when(search.search(any())).thenReturn(new AiListingSearchResponse(List.of(id), 1, Instant.now()),
                new AiListingSearchResponse(List.of(), 0, Instant.now()));
        var value = new AiListingValueService(jdbc, search, new BigDecimal("500000"), 8);
        var result = value.find(new AiListingValueRequest(request(List.of(Condition.leaf("rent.monthly_amount", "lt", "4500000"))), List.of(id)));
        assertTrue(result.candidateIds().isEmpty());
        assertEquals(new BigDecimal("4500000"), result.budgetCeiling());
        assertEquals(new BigDecimal("500000"), result.maxExtraMonthly());
        verify(search, times(4)).search(any()); // Membership check + three bounded retrieval orders.
    }

    @Test void shortlistOnlyRequestsNeverSearchOutsideThatScope() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var search = mock(AiListingSearchService.class);
        String id = "00000000-0000-0000-0000-000000000001";
        var scoped = new AiListingSearchRequest(null, "newest", 4, null, null, 2,
                Condition.allOf(List.of(Condition.leaf("rent.monthly_amount", "lt", "4500000"))), null, null, List.of(id), null, false);
        var result = new AiListingValueService(jdbc, search, new BigDecimal("500000"), 8)
                .find(new AiListingValueRequest(scoped, List.of(id)));
        assertTrue(result.candidateIds().isEmpty());
        verifyNoInteractions(jdbc, search);
    }
}
