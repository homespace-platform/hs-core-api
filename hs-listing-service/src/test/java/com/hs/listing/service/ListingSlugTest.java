package com.hs.listing.service;

import com.hs.listing.model.Listing;
import com.hs.listing.repository.ListingAddressRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.FurnishingItemRepository;
import com.hs.listing.repository.PropertyBranchRepository;
import com.hs.storage.repository.StorageObjectRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ListingSlugTest {
    @Test
    void slugifyStripsVietnameseDiacriticsAndDStroke() {
        assertEquals("phong-tro-day-du-tien-nghi-quan-1", ListingSlugGenerator.slugify("Phòng trọ ĐẦY ĐỦ tiện nghi, Quận 1"));
        assertEquals("duong-dong-da", ListingSlugGenerator.slugify("đường Đống Đa"));
    }

    @Test
    void slugifyCollapsesSymbolsAndFallsBackWhenEmpty() {
        assertEquals("a-b-c", ListingSlugGenerator.slugify("  --A!!  @@b__c** "));
        assertEquals("tin-dang", ListingSlugGenerator.slugify("!!! ???"));
        assertEquals("tin-dang", ListingSlugGenerator.slugify(null));
    }

    @Test
    void slugifyCutsLongTitleOnDashBoundary() {
        String title = "word ".repeat(40);
        String slug = ListingSlugGenerator.slugify(title);
        assertTrue(slug.length() <= ListingSlugGenerator.MAX_TITLE_CHARS);
        assertFalse(slug.endsWith("-"));
        assertTrue(slug.matches("(word-)*word"));
        assertTrue(ListingSlugGenerator.slugify("x".repeat(200)).length() <= ListingSlugGenerator.MAX_TITLE_CHARS);
    }

    @Test
    void generateAppendsEightCharCodeAndRetriesOnCollision() {
        AtomicInteger calls = new AtomicInteger();
        String slug = ListingSlugGenerator.generate("Nhà đẹp", s -> calls.incrementAndGet() < 3);
        assertEquals(3, calls.get());
        assertTrue(slug.matches("nha-dep-[a-z0-9]{8}"), slug);
    }

    @Test
    void generateGivesUpAfterBoundedRetries() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> ListingSlugGenerator.generate("x", s -> { calls.incrementAndGet(); return true; }));
        assertEquals(ListingSlugGenerator.MAX_ATTEMPTS, calls.get());
    }

    @Test
    void slugIsAssignedOnceAndNotChangedByTitleEdit() {
        var repository = mock(ListingRepository.class);
        when(repository.existsBySlug(anyString())).thenReturn(true, false);
        var service = new ListingService(repository, mock(ListingAddressRepository.class), mock(StorageObjectRepository.class),
                mock(com.hs.storage.service.StorageService.class), mock(AmenityRepository.class), mock(FurnishingItemRepository.class),
                mock(ListingStatusService.class), mock(PropertyBranchRepository.class));
        Listing listing = new Listing();
        listing.setTitle("Căn hộ Quận 7");
        service.ensureSlug(listing);
        String slug = listing.getSlug();
        assertTrue(slug.startsWith("can-ho-quan-7-"));
        verify(repository, times(2)).existsBySlug(anyString());

        listing.setTitle("Tiêu đề hoàn toàn khác");
        service.ensureSlug(listing);
        assertEquals(slug, listing.getSlug());
        verifyNoMoreInteractions(repository);
    }
}
