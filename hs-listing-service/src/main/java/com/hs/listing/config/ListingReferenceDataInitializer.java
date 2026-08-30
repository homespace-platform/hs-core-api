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
import java.util.Set;
import java.util.UUID;

@Component
@Order(20)
@RequiredArgsConstructor
public class ListingReferenceDataInitializer implements CommandLineRunner {
    private static final Set<ListingCategory> ALL = Set.of(ListingCategory.values());
    private static final Set<ListingCategory> HOME = Set.of(
            ListingCategory.APARTMENT, ListingCategory.HOUSE, ListingCategory.ROOM);

    private final AmenityRepository amenityRepository;
    private final FurnishingItemRepository furnishingItemRepository;

    @Override
    public void run(String... args) {
        int order = 10;
        amenity("WIFI", "WiFi", order++, ALL);
        amenity("AIR_CONDITIONER", "Máy lạnh", order++, Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE));
        amenity("WATER_HEATER", "Máy nước nóng", order++, Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE));
        amenity("REFRIGERATOR", "Tủ lạnh", order++, Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE));
        amenity("WASHING_MACHINE", "Máy giặt", order++, Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE));
        amenity("ELEVATOR", "Thang máy", order++, ALL);
        amenity("PARKING", "Chỗ để xe", order++, Set.of(ListingCategory.APARTMENT));
        amenity("SECURITY_24_7", "Bảo vệ 24/7", order++, ALL);
        amenity("SECURITY", "Bảo vệ", order++, Set.of(ListingCategory.COMMERCIAL_SPACE));
        amenity("CAMERA", "Camera", order++, ALL);
        amenity("PETS_ALLOWED", "Cho nuôi thú cưng", order++, HOME);
        amenity("SWIMMING_POOL", "Hồ bơi", order++, HOME);
        amenity("GYM", "Phòng gym", order++, HOME);
        amenity("RECEPTION", "Lễ tân", order++, Set.of(ListingCategory.OFFICE));
        amenity("GENERATOR", "Máy phát điện", order++, Set.of(ListingCategory.OFFICE));
        amenity("CENTRAL_AIR_CONDITIONING", "Điều hòa trung tâm", order++, Set.of(ListingCategory.OFFICE));
        amenity("MEETING_ROOM", "Phòng họp", order++, Set.of(ListingCategory.OFFICE));
        amenity("INTERNET", "Internet", order++, Set.of(ListingCategory.OFFICE));
        amenity("FIRE_SAFETY", "Hệ thống PCCC", order++, Set.of(ListingCategory.OFFICE));
        amenity("SIGNAGE_POSITION", "Vị trí đặt biển hiệu", order, Set.of(ListingCategory.COMMERCIAL_SPACE));

        furnishing("BED", "Giường", 10);
        furnishing("WARDROBE", "Tủ quần áo", 20);
        furnishing("WORK_DESK", "Bàn làm việc", 30);
        furnishing("KITCHEN_SHELF", "Kệ bếp", 40);
        furnishing("REFRIGERATOR", "Tủ lạnh", 50);
        furnishing("WASHING_MACHINE", "Máy giặt", 60);
        furnishing("AIR_CONDITIONER", "Máy lạnh", 70);
        furnishing("WATER_HEATER", "Máy nước nóng", 80);
        furnishing("CURTAIN", "Rèm cửa", 90);
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

    private void furnishing(String code, String name, int sortOrder) {
        if (furnishingItemRepository.existsByCode(code)) return;
        FurnishingItem item = new FurnishingItem();
        item.setId(stableId("furnishing:" + code));
        item.setCode(code);
        item.setName(name);
        item.setSortOrder(sortOrder);
        item.setActive(true);
        furnishingItemRepository.save(item);
    }

    private String stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
