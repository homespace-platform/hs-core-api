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
    private static final Set<ListingCategory> ALL = Set.of(
            ListingCategory.APARTMENT, ListingCategory.HOUSE, ListingCategory.ROOM);
    private static final Set<ListingCategory> HOME = ALL;

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
        amenity("CAMERA", "Camera", order++, ALL);
        amenity("PETS_ALLOWED", "Cho nuôi thú cưng", order++, HOME);
        amenity("SWIMMING_POOL", "Hồ bơi", order++, HOME);
        amenity("GYM", "Phòng gym", order++, HOME);

        seedFurnishings();
    }

    private void seedFurnishings() {
        Set<ListingCategory> apartmentHouse = Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE);
        int order = 10;

        // Dùng chung cho nhà ở
        furnishing("BED", "Giường", order++, HOME);
        furnishing("WARDROBE", "Tủ quần áo", order++, HOME);
        furnishing("WORK_DESK", "Bàn làm việc", order++, HOME);
        furnishing("KITCHEN_SHELF", "Kệ bếp", order++, HOME);
        furnishing("REFRIGERATOR", "Tủ lạnh", order++, HOME);
        furnishing("WASHING_MACHINE", "Máy giặt", order++, HOME);
        furnishing("WATER_HEATER", "Máy nước nóng", order++, HOME);
        furnishing("CURTAIN", "Rèm cửa", order++, HOME);
        furnishing("FAN", "Quạt", order++, HOME);

        // Riêng căn hộ và nhà nguyên căn
        furnishing("SOFA_SET", "Bộ sofa + bàn trà", order++, apartmentHouse);
        furnishing("DINING_SET", "Bàn ăn + ghế", order++, apartmentHouse);
        furnishing("TV", "Tivi", order++, apartmentHouse);
        furnishing("KITCHEN_CABINET", "Tủ bếp", order++, apartmentHouse);
        furnishing("COOKTOP", "Bếp từ / bếp gas", order++, apartmentHouse);
        furnishing("RANGE_HOOD", "Máy hút mùi", order++, apartmentHouse);
        furnishing("MICROWAVE", "Lò vi sóng", order++, apartmentHouse);
        furnishing("SHOE_CABINET", "Tủ giày", order++, apartmentHouse);
        furnishing("WATER_TANK_PUMP", "Bồn nước / máy bơm", order++, Set.of(ListingCategory.HOUSE));

        // Dùng chung nhiều loại hình
        furnishing("AIR_CONDITIONER", "Máy lạnh", order++, ALL);
        furnishing("LIGHTING", "Hệ thống đèn chiếu sáng", order++, ALL);
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
