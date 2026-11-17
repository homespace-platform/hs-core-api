package com.hs.listing.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ListingSlugBackfillTest {
    @Test
    void assignsSlugOnlyToRowsReturnedAsNullAndGuardsUpdateWithSlugIsNull() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("slug IS NULL"), eq(ListingSlugBackfill.BATCH_SIZE)))
                .thenReturn(List.of(Map.of("id", "a", "title", "Phòng đẹp"), Map.of("id", "b", "title", "")))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Integer.class), anyString())).thenReturn(0);
        when(jdbc.update(contains("slug IS NULL"), anyString(), anyString())).thenReturn(1);

        new ListingSlugBackfill(jdbc).backfill();

        var slugs = ArgumentCaptor.forClass(String.class);
        var ids = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).update(contains("WHERE id = ? AND slug IS NULL"), slugs.capture(), ids.capture());
        assertEquals(List.of("a", "b"), ids.getAllValues());
        assertTrue(slugs.getAllValues().get(0).matches("phong-dep-[a-z0-9]{8}"));
        assertTrue(slugs.getAllValues().get(1).matches("tin-dang-[a-z0-9]{8}"));
        verify(jdbc, times(2)).queryForList(anyString(), eq(ListingSlugBackfill.BATCH_SIZE));
    }

    @Test
    void doesNothingWhenEveryListingAlreadyHasSlug() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(ListingSlugBackfill.BATCH_SIZE))).thenReturn(List.of());
        new ListingSlugBackfill(jdbc).backfill();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
}
