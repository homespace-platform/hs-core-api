package com.hs.listing.config;

import com.hs.listing.model.constant.ListingCategory;
import java.util.List;
import java.util.Set;

/** Seed reference data. Runtime catalogs read the database tables, which may contain more items. */
public final class ListingReferenceSeeds {
    private ListingReferenceSeeds() {}

    public record Item(String code, String name, Set<ListingCategory> categories) {}

    private static final Set<ListingCategory> ALL = Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE, ListingCategory.ROOM);
    private static final Set<ListingCategory> APARTMENT_HOUSE = Set.of(ListingCategory.APARTMENT, ListingCategory.HOUSE);

    public static final List<Item> AMENITIES = List.of(
            new Item("WIFI", "WiFi", ALL),
            new Item("AIR_CONDITIONER", "Máy lạnh", APARTMENT_HOUSE),
            new Item("WATER_HEATER", "Máy nước nóng", APARTMENT_HOUSE),
            new Item("REFRIGERATOR", "Tủ lạnh", APARTMENT_HOUSE),
            new Item("WASHING_MACHINE", "Máy giặt", APARTMENT_HOUSE),
            new Item("ELEVATOR", "Thang máy", ALL),
            new Item("PARKING", "Chỗ để xe", Set.of(ListingCategory.APARTMENT)),
            new Item("SECURITY_24_7", "Bảo vệ 24/7", ALL),
            new Item("CAMERA", "Camera", ALL),
            new Item("SWIMMING_POOL", "Hồ bơi", ALL),
            new Item("GYM", "Phòng gym", ALL));

    public static final List<Item> FURNISHINGS = List.of(
            new Item("BED", "Giường", ALL),
            new Item("WARDROBE", "Tủ quần áo", ALL),
            new Item("WORK_DESK", "Bàn làm việc", ALL),
            new Item("KITCHEN_SHELF", "Kệ bếp", ALL),
            new Item("REFRIGERATOR", "Tủ lạnh", ALL),
            new Item("WASHING_MACHINE", "Máy giặt", ALL),
            new Item("WATER_HEATER", "Máy nước nóng", ALL),
            new Item("CURTAIN", "Rèm cửa", ALL),
            new Item("FAN", "Quạt", ALL),
            new Item("SOFA_SET", "Bộ sofa + bàn trà", APARTMENT_HOUSE),
            new Item("DINING_SET", "Bàn ăn + ghế", APARTMENT_HOUSE),
            new Item("TV", "Tivi", APARTMENT_HOUSE),
            new Item("KITCHEN_CABINET", "Tủ bếp", APARTMENT_HOUSE),
            new Item("COOKTOP", "Bếp từ / bếp gas", APARTMENT_HOUSE),
            new Item("RANGE_HOOD", "Máy hút mùi", APARTMENT_HOUSE),
            new Item("MICROWAVE", "Lò vi sóng", APARTMENT_HOUSE),
            new Item("SHOE_CABINET", "Tủ giày", APARTMENT_HOUSE),
            new Item("WATER_TANK_PUMP", "Bồn nước / máy bơm", Set.of(ListingCategory.HOUSE)),
            new Item("AIR_CONDITIONER", "Máy lạnh", ALL),
            new Item("LIGHTING", "Hệ thống đèn chiếu sáng", ALL));
}
