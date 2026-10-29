package com.hs.listing.config;

import com.hs.listing.model.Amenity;
import com.hs.listing.model.FurnishingItem;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.FurnishingItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
@Order(20)
@RequiredArgsConstructor
public class ListingReferenceDataInitializer implements CommandLineRunner {
    private final AmenityRepository amenityRepository;
    private final FurnishingItemRepository furnishingItemRepository;

    @Override
    public void run(String... args) {
        // Pets are a listing policy, not an amenity. Retire the old catalog entry without
        // deleting historical associations or signed contractual snapshots.
        amenityRepository.findByCode("PETS_ALLOWED").ifPresent(a -> { a.setActive(false); amenityRepository.save(a); });
        int order = 10;
        for (ListingReferenceSeeds.Item item : ListingReferenceSeeds.AMENITIES) {
            amenity(item.code(), item.name(), order++, item.categories());
        }
        order = 10;
        for (ListingReferenceSeeds.Item item : ListingReferenceSeeds.FURNISHINGS) {
            furnishing(item.code(), item.name(), order++, item.categories());
        }
    }

    private void amenity(String code, String name, int sortOrder, Set<ListingCategory> categories) {
        if (amenityRepository.existsByCode(code)) return;
        Amenity item = new Amenity();
        item.setId(stableId("amenity:" + code));
        item.setCode(code);
        item.setName(name);
        item.setSortOrder(sortOrder);
        item.setCategories(categories);
        item.setActive(true);
        amenityRepository.save(item);
    }

    /** Upsert để các item đã seed trước đây được backfill category mapping. */
    private void furnishing(String code, String name, int sortOrder, Set<ListingCategory> categories) {
        FurnishingItem item = furnishingItemRepository.findByCode(code).orElseGet(() -> {
            FurnishingItem created = new FurnishingItem();
            created.setId(stableId("furnishing:" + code));
            created.setCode(code);
            return created;
        });
        item.setName(name);
        item.setSortOrder(sortOrder);
        item.setCategories(new HashSet<>(categories));
        item.setActive(true);
        furnishingItemRepository.save(item);
    }

    private String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
