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

        seedFurnishings();
    }

    private void seedFurnishings() {
        Set<ListingCategory> apartmentHouse = Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE);
        Set<ListingCategory> office = Set.of(ListingCategory.OFFICE);
        Set<ListingCategory> commercial = Set.of(ListingCategory.COMMERCIAL_SPACE);
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

        // Văn phòng
        furnishing("OFFICE_DESK", "Bàn làm việc nhân viên", order++, office);
        furnishing("OFFICE_CHAIR", "Ghế xoay văn phòng", order++, office);
        furnishing("FILING_CABINET", "Tủ hồ sơ", order++, office);
        furnishing("PARTITION", "Vách ngăn", order++, office);
        furnishing("MEETING_TABLE", "Bàn họp + ghế", order++, office);
        furnishing("RECEPTION_COUNTER", "Quầy lễ tân", order++, office);
        furnishing("NETWORK_CABLING", "Hệ thống mạng LAN / ổ cắm", order++, office);
        furnishing("PROJECTOR_TV", "Máy chiếu / TV phòng họp", order++, office);
        furnishing("PANTRY_EQUIPMENT", "Thiết bị pantry", order++, office);
        furnishing("CURTAIN_BLIND", "Rèm cửa / mành", order++, office);

        // Mặt bằng kinh doanh
        furnishing("ROLLING_DOOR", "Cửa cuốn / cửa kính", order++, commercial);
        furnishing("DISPLAY_SHELF", "Kệ trưng bày", order++, commercial);
        furnishing("CASHIER_COUNTER", "Quầy thu ngân", order++, commercial);
        furnishing("SIGNAGE_FRAME", "Khung / bảng hiệu", order++, commercial);
        furnishing("ELECTRICAL_SYSTEM", "Hệ thống điện", order++, commercial);
        furnishing("WATER_SYSTEM", "Hệ thống cấp thoát nước", order++, commercial);
        furnishing("RESTROOM_FIXTURE", "Thiết bị nhà vệ sinh", order++, commercial);
        furnishing("MEZZANINE_STRUCTURE", "Kết cấu gác lửng", order++, commercial);
        furnishing("WAREHOUSE_SHELF", "Kệ kho / khu vực kho", order++, commercial);

        // Dùng chung nhiều loại hình
        furnishing("AIR_CONDITIONER", "Máy lạnh", order++, ALL);
        furnishing("LIGHTING", "Hệ thống đèn chiếu sáng", order++, ALL);
        furnishing("CEILING_FLOOR_FINISH", "Trần / sàn hoàn thiện", order++,
                Set.of(ListingCategory.OFFICE, ListingCategory.COMMERCIAL_SPACE));
        furnishing("FIRE_EQUIPMENT", "Thiết bị PCCC", order,
                Set.of(ListingCategory.OFFICE, ListingCategory.COMMERCIAL_SPACE));
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
