package com.hs.listing.service;

import com.hs.listing.config.ListingPolicyCatalog;
import com.hs.listing.config.ListingReferenceSeeds;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Versioned catalog of everything the AI may ask Core to filter, compute or merely display. Amenities,
 * furnishing items and policies come from the live reference tables, so a new item becomes searchable
 * without a code change. SQL identifiers used by the compiler come only from this class.
 */
@Service
public class AiListingFieldCatalog {
    public static final int VERSION = 2;

    public enum Kind { TEXT, NUMBER, INTEGER, BOOLEAN, DATE, ENUM }
    public enum Role { FILTER, COMPUTED, DISPLAY_ONLY }
    enum Binding { LISTING, DETAIL, ROW, PRESENCE, SCALAR, AGGREGATE, AMENITY_CODE, NONE }

    public record Choice(String code, String label) {}
    public record FieldSpec(String code, String label, Kind kind, List<String> operators, String unit, List<String> categories,
                            List<Choice> values, String source, String nullMeaning, Role role, List<String> requiresContext) {}
    public record Unsupported(String code, String label, String reason) {}
    public record CatalogView(int version, String catalogVersion, List<FieldSpec> fields, List<Unsupported> unsupported,
                              List<String> sorts, List<String> contextFields, List<Map<String, String>> nearbyKinds) {}

    /** SQL binding. {@code ordinal} lists enum values from best to worst for ordered comparisons. */
    record Def(FieldSpec spec, Binding binding, String table, String column, String fixed, String rowKey, List<String> ordinal) {}
    record Snapshot(String version, Map<String, Def> fields) {}

    public record RefItem(String code, String name, List<String> categories) {}
    public record PolicyItem(String code, String label, String valueType, Integer min, Integer max, String unit, List<String> categories) {}
    public record Reference(List<RefItem> amenities, List<RefItem> furnishings, List<PolicyItem> policies) {}

    public static final List<String> SORTS = List.of("newest", "price_asc", "price_desc", "area_asc", "area_desc",
            "rent_asc", "fixed_cost_asc", "estimated_cost_asc", "target_distance");
    public static final List<String> CONTEXT_FIELDS = List.of("occupants", "motorbikes", "cars", "electricityKwh", "waterM3");

    private static final List<String> ALL_CATEGORIES = List.of("APARTMENT", "HOUSE", "ROOM");
    private static final List<String> TEXT_OPS = List.of("eq", "ne", "contains");
    private static final List<String> ENUM_OPS = List.of("eq", "ne", "in");
    private static final List<String> ORDINAL_OPS = List.of("eq", "ne", "in", "gte", "gt", "lte", "lt");
    private static final List<String> NUMBER_OPS = List.of("eq", "ne", "gt", "gte", "lt", "lte");
    private static final List<String> BOOLEAN_OPS = List.of("eq", "ne");

    private static final String NULL_UNKNOWN = "Thiếu dữ liệu là chưa rõ, không phải không có hoặc bằng 0.";
    private static final String NULL_UNDECLARED = "Tin không khai báo thì không khớp điều kiện bắt buộc, nhưng không được nói là không có.";

    private final NamedParameterJdbcTemplate jdbc;
    private final Duration ttl;
    private final Clock clock;
    private final Reference fixedReference;
    private volatile Snapshot cached;
    private volatile Instant loadedAt;

    @Autowired
    public AiListingFieldCatalog(NamedParameterJdbcTemplate jdbc, @Value("${listing.ai.catalog-cache-seconds}") long cacheSeconds) {
        if (cacheSeconds < 1 || cacheSeconds > 86400) {
            throw new IllegalStateException("listing.ai.catalog-cache-seconds must be between 1 and 86400");
        }
        this.jdbc = Objects.requireNonNull(jdbc);
        this.ttl = Duration.ofSeconds(cacheSeconds);
        this.clock = Clock.systemUTC();
        this.fixedReference = null;
    }

    private AiListingFieldCatalog(Reference reference) {
        this.jdbc = null;
        this.ttl = null;
        this.clock = Clock.systemUTC();
        this.fixedReference = reference;
        this.cached = build(reference);
    }

    /** Offline catalog from the seed definitions; used by unit tests and code that has no database. */
    public static AiListingFieldCatalog seeded() {
        return of(seedReference());
    }

    public static AiListingFieldCatalog of(Reference reference) {
        return new AiListingFieldCatalog(reference);
    }

    public static Reference seedReference() {
        List<RefItem> amenities = ListingReferenceSeeds.AMENITIES.stream()
                .map(i -> new RefItem(i.code(), i.name(), i.categories().stream().map(Enum::name).sorted().toList())).toList();
        List<RefItem> furnishings = ListingReferenceSeeds.FURNISHINGS.stream()
                .map(i -> new RefItem(i.code(), i.name(), i.categories().stream().map(Enum::name).sorted().toList())).toList();
        List<PolicyItem> policies = ListingPolicyCatalog.defaults().stream()
                .map(p -> new PolicyItem(p.getCode(), p.getLabel(), p.getValueType(), p.getMinimumValue(), p.getMaximumValue(),
                        p.getUnitLabel(), p.getCategories().stream().map(Enum::name).sorted().toList())).toList();
        return new Reference(amenities, furnishings, policies);
    }

    Snapshot snapshot() {
        if (fixedReference != null) return cached;
        Snapshot current = cached;
        Instant now = clock.instant();
        if (current != null && loadedAt != null && now.isBefore(loadedAt.plus(ttl))) return current;
        synchronized (this) {
            if (cached != null && loadedAt != null && clock.instant().isBefore(loadedAt.plus(ttl))) return cached;
            cached = build(loadLive());
            loadedAt = clock.instant();
            return cached;
        }
    }

    public String version() { return snapshot().version(); }

    /** v1 contract: code to primitive kind. Enumerations stay TEXT so older clients keep working. */
    public Map<String, String> legacyFields() {
        Map<String, String> result = new LinkedHashMap<>();
        snapshot().fields().forEach((code, def) -> {
            if (def.spec().role() != Role.DISPLAY_ONLY) {
                result.put(code, def.spec().kind() == Kind.ENUM ? "TEXT" : def.spec().kind().name());
            }
        });
        return result;
    }

    public CatalogView view(List<Map<String, String>> nearbyKinds) {
        Snapshot snapshot = snapshot();
        return new CatalogView(VERSION, snapshot.version(), snapshot.fields().values().stream().map(Def::spec).toList(),
                UNSUPPORTED, SORTS, CONTEXT_FIELDS, nearbyKinds);
    }

    static final List<Unsupported> UNSUPPORTED = List.of(
            new Unsupported("QUIETNESS", "Yên tĩnh/ồn ào", "Không có dữ liệu đo độ ồn; số địa điểm xung quanh không chứng minh yên tĩnh."),
            new Unsupported("SECURITY_QUALITY", "An ninh tốt/xấu", "Chỉ có tiện ích khai báo như bảo vệ 24/7 hoặc camera, không có đánh giá an ninh."),
            new Unsupported("TRAVEL_TIME", "Thời gian đi lại/đường đi", "Chỉ có khoảng cách đường chim bay, không có tuyến đường hay số phút."),
            new Unsupported("FLOODING", "Ngập nước", "Không có dữ liệu ngập."),
            new Unsupported("LANDLORD_COHABITATION", "Ở chung/không chung chủ", "Không có trường chủ nhà ở cùng; chỉ có lối đi riêng/chung và giờ giấc."),
            new Unsupported("VIEWING_SLOT_AVAILABILITY", "Lịch xem còn trống thực tế", "Chỉ có ngày/buổi xem chủ nhà khai báo, không phải lịch trống."),
            new Unsupported("HIGHLIGHTS", "Điểm nổi bật", "Do ứng dụng tổng hợp để hiển thị, không phải điều kiện lọc."),
            new Unsupported("NEARBY_SPORTS", "Phòng gym/thể thao gần nhà", "Nhóm thể thao chưa được thu thập quanh tin; amenity.GYM chỉ là gym trong tòa nhà, không chứng minh gần gym."));

    private Reference loadLive() {
        Map<String, Object> none = Map.of();
        List<RefItem> amenities = jdbc.query("""
                SELECT a.code, a.name, string_agg(c.category, ',' ORDER BY c.category) AS categories
                FROM amenities a LEFT JOIN amenity_categories c ON c.amenity_id = a.id
                WHERE a.active = true GROUP BY a.code, a.name, a.sort_order ORDER BY a.sort_order, a.code""", none,
                (rs, i) -> new RefItem(rs.getString("code"), rs.getString("name"), split(rs.getString("categories"))));
        List<RefItem> furnishings = jdbc.query("""
                SELECT f.code, f.name, string_agg(c.category, ',' ORDER BY c.category) AS categories
                FROM furnishing_items f LEFT JOIN furnishing_item_categories c ON c.furnishing_item_id = f.id
                WHERE f.active = true GROUP BY f.code, f.name, f.sort_order ORDER BY f.sort_order, f.code""", none,
                (rs, i) -> new RefItem(rs.getString("code"), rs.getString("name"), split(rs.getString("categories"))));
        List<PolicyItem> policies = jdbc.query("""
                SELECT d.code, d.label, d.value_type, d.minimum_value, d.maximum_value, d.unit_label,
                       string_agg(c.category, ',' ORDER BY c.category) AS categories
                FROM listing_policy_definitions d LEFT JOIN listing_policy_definition_categories c ON c.policy_code = d.code
                WHERE d.active = true
                GROUP BY d.code, d.label, d.value_type, d.minimum_value, d.maximum_value, d.unit_label, d.sort_order
                ORDER BY d.sort_order, d.code""", none,
                (rs, i) -> new PolicyItem(rs.getString("code"), rs.getString("label"), rs.getString("value_type"),
                        (Integer) rs.getObject("minimum_value"), (Integer) rs.getObject("maximum_value"),
                        rs.getString("unit_label"), split(rs.getString("categories"))));
        return new Reference(amenities, furnishings, policies);
    }

    private static List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(","));
    }

    // ---------------------------------------------------------------- registry

    private static final List<Choice> CATEGORY = choices("APARTMENT", "Căn hộ/chung cư", "HOUSE", "Nhà nguyên căn", "ROOM", "Phòng trọ");
    private static final List<Choice> PRICE_UNIT = choices("MONTH", "Theo tháng (cả căn)", "M2_MONTH", "Theo m²/tháng",
            "ROOM_MONTH", "Theo phòng/tháng", "PERSON_MONTH", "Theo người/tháng", "SEAT_MONTH", "Theo chỗ/tháng");
    private static final List<Choice> DEPOSIT_TYPE = choices("NONE", "Không cọc", "FIXED_AMOUNT", "Cọc số tiền cố định",
            "MONTH_COUNT", "Cọc theo số tháng thuê", "NEGOTIABLE", "Cọc thỏa thuận");
    private static final List<Choice> PAYMENT_CYCLE = choices("MONTHLY", "Trả hằng tháng", "EVERY_2_MONTHS", "2 tháng/lần",
            "QUARTERLY", "3 tháng/lần", "EVERY_6_MONTHS", "6 tháng/lần", "NEGOTIABLE", "Thỏa thuận");
    private static final List<Choice> FURNISHING_STATUS = choices("UNFURNISHED", "Không nội thất", "BASIC", "Nội thất cơ bản",
            "PARTIALLY_FURNISHED", "Một phần nội thất", "FULLY_FURNISHED", "Đầy đủ nội thất", "LUXURY", "Nội thất cao cấp");
    static final List<Choice> HANDOVER = choices("BRAND_NEW", "Mới", "GOOD", "Còn tốt", "NORMAL", "Bình thường",
            "USED_ACCEPTABLE", "Đã qua sử dụng, dùng được", "MINOR_DAMAGE", "Hư hỏng nhẹ");
    private static final List<Choice> PRIVATE_SHARED = choices("PRIVATE", "Riêng", "SHARED", "Chung");
    private static final List<Choice> PRIVATE_SHARED_NONE = choices("PRIVATE", "Riêng", "SHARED", "Chung", "NONE", "Không có");
    private static final List<Choice> ACCESS_TYPE = choices("PRIVATE", "Lối đi riêng", "SHARED", "Lối đi chung");
    private static final List<Choice> ACCESS_HOURS = choices("FLEXIBLE", "Giờ giấc tự do", "CURFEW", "Có giờ đóng cửa");
    private static final List<Choice> METER = choices("PRIVATE", "Đồng hồ riêng", "SHARED", "Đồng hồ chung");
    private static final List<Choice> PARKING_POLICY = choices("NONE", "Không nhận gửi xe", "FREE", "Gửi xe không thu phí riêng", "PAID", "Gửi xe có phí");
    private static final List<Choice> BILLING = choices("PER_KWH", "Theo kWh", "STATE_WATER_RATE", "Theo giá nước nhà nước", "PER_M3", "Theo m³",
            "PER_PERSON_MONTH", "Theo người/tháng", "PER_MONTH", "Cố định/tháng", "PER_M2_MONTH", "Theo m²/tháng",
            "PER_VEHICLE_MONTH", "Theo xe/tháng", "PER_HOUR", "Theo giờ", "FREE", "Miễn phí", "INCLUDED", "Đã gồm trong giá thuê",
            "NOT_APPLICABLE", "Không áp dụng", "NEGOTIABLE", "Thỏa thuận", "CUSTOM", "Tùy chỉnh");
    private static final List<Choice> DAYS = choices("MONDAY", "Thứ Hai", "TUESDAY", "Thứ Ba", "WEDNESDAY", "Thứ Tư",
            "THURSDAY", "Thứ Năm", "FRIDAY", "Thứ Sáu", "SATURDAY", "Thứ Bảy", "SUNDAY", "Chủ nhật");
    private static final List<Choice> SLOTS = choices("MORNING", "Buổi sáng", "AFTERNOON", "Buổi chiều", "EVENING", "Buổi tối");
    private static final List<Choice> PERMISSION = choices("ALLOWED", "Cho phép", "NOT_ALLOWED", "Không cho phép", "APPROVAL_REQUIRED", "Cần chủ nhà đồng ý");
    private static final Map<String, String> CHARGE_LABELS = Map.of("ELECTRICITY", "Tiền điện", "WATER", "Tiền nước",
            "MANAGEMENT", "Phí quản lý", "INTERNET", "Internet", "SERVICE_OR_GARBAGE", "Phí dịch vụ/rác",
            "MOTORBIKE_PARKING", "Phí gửi xe máy", "CAR_PARKING", "Phí gửi ô tô",
            "OVERTIME_AIR_CONDITIONING", "Điều hòa ngoài giờ", "OTHER", "Phí khác");
    private static final Map<String, String> SCOPE_LABELS = Map.of("room", "phòng trọ", "house", "nhà nguyên căn", "apartment", "căn hộ");

    private static List<Choice> choices(String... pairs) {
        List<Choice> result = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) result.add(new Choice(pairs[i], pairs[i + 1]));
        return List.copyOf(result);
    }

    private static Snapshot build(Reference reference) {
        Map<String, Def> fields = new LinkedHashMap<>();
        // Listing columns.
        listing(fields, "id", "Mã tin", Kind.TEXT, null, null);
        listing(fields, "title", "Tiêu đề", Kind.TEXT, null, null);
        listing(fields, "description", "Mô tả", Kind.TEXT, null, null);
        listing(fields, "category", "Loại hình", Kind.ENUM, null, CATEGORY);
        listing(fields, "price_unit", "Đơn vị giá thuê", Kind.ENUM, null, PRICE_UNIT);
        listing(fields, "currency", "Tiền tệ", Kind.TEXT, null, null);
        listing(fields, "deposit_type", "Hình thức đặt cọc", Kind.ENUM, null, DEPOSIT_TYPE);
        listing(fields, "payment_cycle", "Chu kỳ thanh toán", Kind.ENUM, null, PAYMENT_CYCLE);
        listing(fields, "price_amount", "Giá niêm yết (theo đơn vị giá)", Kind.NUMBER, "VND", null);
        listing(fields, "area_m2", "Diện tích", Kind.NUMBER, "m²", null);
        listing(fields, "deposit_amount", "Tiền cọc cố định", Kind.NUMBER, "VND", null);
        listing(fields, "deposit_months", "Số tháng cọc", Kind.INTEGER, "tháng", null);
        listing(fields, "minimum_lease_months", "Thời hạn thuê tối thiểu", Kind.INTEGER, "tháng", null);
        listing(fields, "max_motorbike_count", "Số xe máy tối đa", Kind.INTEGER, "xe", null);
        listing(fields, "max_car_count", "Số ô tô tối đa", Kind.INTEGER, "xe", null);
        listing(fields, "negotiable", "Giá có thương lượng", Kind.BOOLEAN, null, null);
        listing(fields, "management_fee_included", "Đã gồm phí quản lý", Kind.BOOLEAN, null, null);
        listing(fields, "available_from", "Ngày có thể dọn vào", Kind.DATE, null, null);
        listing(fields, "published_at", "Ngày đăng", Kind.DATE, null, null);
        listing(fields, "expires_at", "Ngày hết hạn tin", Kind.DATE, null, null);
        listing(fields, "view_count", "Lượt xem", Kind.INTEGER, null, null);
        // Address (one active row per listing).
        for (String[] a : new String[][]{{"city_id", "Mã tỉnh/thành"}, {"city", "Tỉnh/thành"}, {"ward_id", "Mã phường/xã"},
                {"ward", "Phường/xã"}, {"street", "Đường"}, {"display", "Địa chỉ hiển thị"}}) {
            detail(fields, a[0], a[1], "listing_addresses", a[0], Kind.TEXT, null, null, ALL_CATEGORIES);
        }
        // Category details.
        String h = "listing_house_details";
        for (String[] c : new String[][]{{"land_area_m2", "Diện tích đất", "m²"}, {"frontage_width_m", "Mặt tiền", "m"},
                {"length_m", "Chiều dài", "m"}, {"access_road_width_m", "Độ rộng đường vào", "m"}}) {
            scoped(fields, "house", h, c[0], c[1], Kind.NUMBER, c[2], null);
        }
        for (String[] c : new String[][]{{"frontage_count", "Số mặt tiền"}, {"total_floors", "Số tầng"}, {"bedroom_count", "Số phòng ngủ"},
                {"bathroom_count", "Số phòng tắm/WC"}, {"living_room_count", "Số phòng khách"}, {"kitchen_count", "Số bếp"},
                {"max_occupants", "Số người ở tối đa"}, {"max_vehicles", "Số xe tối đa"}, {"rented_floor_from", "Tầng thuê từ"},
                {"rented_floor_to", "Tầng thuê đến"}}) {
            scoped(fields, "house", h, c[0], c[1], Kind.INTEGER, null, null);
        }
        scoped(fields, "house", h, "has_rooftop", "Có sân thượng", Kind.BOOLEAN, null, null);
        scoped(fields, "house", h, "has_garage", "Có gara", Kind.BOOLEAN, null, null);
        scoped(fields, "house", h, "access_type", "Lối đi", Kind.ENUM, null, ACCESS_TYPE);
        scoped(fields, "house", h, "furnishing_status", "Mức nội thất", Kind.ENUM, null, FURNISHING_STATUS);
        scoped(fields, "house", h, "legal_status", "Pháp lý", Kind.TEXT, null, null);
        scoped(fields, "house", h, "rental_scope_description", "Phạm vi cho thuê", Kind.TEXT, null, null);
        String ap = "listing_apartment_details";
        for (String[] c : new String[][]{{"project_name", "Tên dự án"}, {"building_block", "Tòa/block"}, {"unit_code", "Mã căn"},
                {"main_door_direction", "Hướng cửa chính"}, {"balcony_direction", "Hướng ban công"}, {"view_description", "Mô tả tầm nhìn"},
                {"legal_status", "Pháp lý"}}) {
            scoped(fields, "apartment", ap, c[0], c[1], Kind.TEXT, null, null);
        }
        scoped(fields, "apartment", ap, "furnishing_status", "Mức nội thất", Kind.ENUM, null, FURNISHING_STATUS);
        for (String[] c : new String[][]{{"floor_number", "Tầng"}, {"building_total_floors", "Tổng số tầng tòa nhà"},
                {"bedroom_count", "Số phòng ngủ"}, {"bathroom_count", "Số phòng tắm/WC"}, {"living_room_count", "Số phòng khách"},
                {"kitchen_count", "Số bếp"}, {"max_occupants", "Số người ở tối đa"}}) {
            scoped(fields, "apartment", ap, c[0], c[1], Kind.INTEGER, null, null);
        }
        String r = "listing_room_details";
        scoped(fields, "room", r, "room_code", "Mã phòng", Kind.TEXT, null, null);
        scoped(fields, "room", r, "restroom_type", "Nhà vệ sinh", Kind.ENUM, null, PRIVATE_SHARED);
        scoped(fields, "room", r, "kitchen_type", "Bếp", Kind.ENUM, null, PRIVATE_SHARED_NONE);
        scoped(fields, "room", r, "balcony_type", "Loại ban công", Kind.ENUM, null, PRIVATE_SHARED_NONE);
        scoped(fields, "room", r, "furnishing_status", "Mức nội thất", Kind.ENUM, null, FURNISHING_STATUS);
        scoped(fields, "room", r, "access_type", "Lối đi", Kind.ENUM, null, ACCESS_TYPE);
        scoped(fields, "room", r, "access_hours_type", "Giờ giấc ra vào", Kind.ENUM, null, ACCESS_HOURS);
        scoped(fields, "room", r, "electric_meter_type", "Đồng hồ điện", Kind.ENUM, null, METER);
        scoped(fields, "room", r, "water_meter_type", "Đồng hồ nước", Kind.ENUM, null, METER);
        scoped(fields, "room", r, "parking_policy", "Chính sách gửi xe", Kind.ENUM, null, PARKING_POLICY);
        scoped(fields, "room", r, "floor_number", "Tầng", Kind.INTEGER, null, null);
        scoped(fields, "room", r, "max_occupants", "Số người ở tối đa", Kind.INTEGER, null, null);
        scoped(fields, "room", r, "max_vehicles", "Số xe tối đa", Kind.INTEGER, null, null);
        scoped(fields, "room", r, "has_window", "Có cửa sổ", Kind.BOOLEAN, null, null);
        scoped(fields, "room", r, "has_balcony", "Có ban công", Kind.BOOLEAN, null, null);
        scoped(fields, "room", r, "has_mezzanine", "Có gác lửng", Kind.BOOLEAN, null, null);
        // Charges: predicates on one charge type share one row.
        for (String type : CHARGE_LABELS.keySet().stream().sorted().toList()) {
            String fixed = "t.charge_type = '" + type + "'";
            String key = "charge:" + type;
            String label = CHARGE_LABELS.get(type);
            row(fields, "charge." + type + ".amount", label + " - đơn giá", "listing_charges", "amount", Kind.NUMBER, "VND", null, fixed, key);
            row(fields, "charge." + type + ".included_in_rent", label + " - đã gồm trong giá thuê", "listing_charges", "included_in_rent", Kind.BOOLEAN, null, null, fixed, key);
            row(fields, "charge." + type + ".billing_method", label + " - cách tính", "listing_charges", "billing_method", Kind.ENUM, null, BILLING, fixed, key);
            row(fields, "charge." + type + ".unit", label + " - đơn vị", "listing_charges", "unit", Kind.TEXT, null, null, fixed, key);
            row(fields, "charge." + type + ".description", label + " - mô tả", "listing_charges", "description", Kind.TEXT, null, null, fixed, key);
            row(fields, "charge." + type + ".custom_name", label + " - tên phí", "listing_charges", "custom_name", Kind.TEXT, null, null, fixed, key);
        }
        // Amenities from the live reference table.
        put(fields, new Def(spec("amenity.code", "Mã tiện ích", Kind.ENUM, BOOLEAN_OPS, null, ALL_CATEGORIES,
                reference.amenities().stream().map(a -> new Choice(a.code(), a.name())).toList(), "listing_amenities",
                NULL_UNDECLARED, Role.FILTER, List.of()), Binding.AMENITY_CODE, "listing_amenities", null, null, null, null));
        for (RefItem amenity : reference.amenities()) {
            put(fields, new Def(spec("amenity." + amenity.code(), "Tiện ích: " + amenity.name(), Kind.BOOLEAN, BOOLEAN_OPS, null,
                    amenity.categories(), null, "listing_amenities", NULL_UNDECLARED, Role.FILTER, List.of()), Binding.PRESENCE,
                    "listing_amenities", null, "EXISTS (SELECT 1 FROM amenities a WHERE a.id = t.amenity_id AND a.code = '" + safe(amenity.code()) + "')", null, null));
        }
        // Furnishing assets: one item code is one row group; total quantity sums the rows that match its group.
        for (RefItem item : reference.furnishings()) {
            String code = safe(item.code());
            String fixed = "t.item_code = '" + code + "'";
            String key = "furnishing:" + code;
            String base = "furnishing." + code;
            put(fields, new Def(spec(base, "Trang thiết bị: " + item.name(), Kind.BOOLEAN, BOOLEAN_OPS, null, item.categories(), null,
                    "listing_furnishing_assets", NULL_UNDECLARED, Role.FILTER, List.of()), Binding.PRESENCE, "listing_furnishing_assets", null, fixed, key, null));
            row(fields, base + ".quantity", item.name() + " - số lượng một dòng", "listing_furnishing_assets", "quantity", Kind.INTEGER, "cái", null, fixed, key);
            put(fields, new Def(spec(base + ".handover_condition", item.name() + " - tình trạng bàn giao", Kind.ENUM, ORDINAL_OPS, null,
                    item.categories(), HANDOVER, "listing_furnishing_assets",
                    "gte GOOD nghĩa là Mới hoặc Còn tốt. " + NULL_UNDECLARED, Role.FILTER, List.of()), Binding.ROW, "listing_furnishing_assets",
                    "handover_condition", fixed, key, HANDOVER.stream().map(Choice::code).toList()));
            row(fields, base + ".asset_name", item.name() + " - tên thiết bị", "listing_furnishing_assets", "asset_name", Kind.TEXT, null, null, fixed, key);
            row(fields, base + ".condition_note", item.name() + " - ghi chú tình trạng", "listing_furnishing_assets", "condition_note", Kind.TEXT, null, null, fixed, key);
            put(fields, new Def(spec(base + ".total_quantity", item.name() + " - tổng số lượng", Kind.INTEGER, NUMBER_OPS, "cái", item.categories(), null,
                    "listing_furnishing_assets", "Cộng số lượng các dòng cùng thiết bị thỏa các điều kiện cùng nhóm (ví dụ tình trạng); không có dòng nào là 0.",
                    Role.COMPUTED, List.of()), Binding.AGGREGATE, "listing_furnishing_assets", "quantity", fixed, key, null));
        }
        row(fields, "furnishing.item_code", "Mã trang thiết bị", "listing_furnishing_assets", "item_code", Kind.TEXT, null, null, null, null);
        row(fields, "furnishing.asset_name", "Tên trang thiết bị", "listing_furnishing_assets", "asset_name", Kind.TEXT, null, null, null, null);
        // Policies: all value types, each scoped to its own row.
        for (PolicyItem policy : reference.policies()) {
            String code = safe(policy.code());
            String fixed = "t.policy_code = '" + code + "'";
            String key = "policy:" + code;
            String base = "policy." + code;
            switch (policy.valueType()) {
                case "PERMISSION" -> put(fields, new Def(spec(base + ".value", policy.label(), Kind.ENUM, ENUM_OPS, null, policy.categories(),
                        PERMISSION, "listing_policy_values", NULL_UNDECLARED, Role.FILTER, List.of()), Binding.ROW, "listing_policy_values",
                        "policy_value", fixed, key, null));
                case "INTEGER" -> put(fields, new Def(spec(base + ".value", policy.label(), Kind.INTEGER, NUMBER_OPS, policy.unit(), policy.categories(),
                        null, "listing_policy_values", "Khoảng hợp lệ " + policy.min() + "-" + policy.max() + ". " + NULL_UNDECLARED,
                        Role.FILTER, List.of()), Binding.ROW, "listing_policy_values",
                        "CASE WHEN t.policy_value ~ '^-?[0-9]{1,9}$' THEN CAST(t.policy_value AS integer) END", fixed, key, null));
                default -> put(fields, new Def(spec(base + ".value", policy.label(), Kind.TEXT, TEXT_OPS, null, policy.categories(), null,
                        "listing_policy_values", NULL_UNDECLARED, Role.FILTER, List.of()), Binding.ROW, "listing_policy_values", "policy_value", fixed, key, null));
            }
            row(fields, base + ".note", policy.label() + " - ghi chú", "listing_policy_values", "note", Kind.TEXT, null, null, fixed, key);
        }
        put(fields, new Def(spec("media.IMAGE", "Có ảnh", Kind.BOOLEAN, BOOLEAN_OPS, null, ALL_CATEGORIES, null, "listing_media", NULL_UNDECLARED,
                Role.FILTER, List.of()), Binding.PRESENCE, "listing_media", null, "t.media_type = 'IMAGE' AND t.active = true", null, null));
        put(fields, new Def(spec("media.VIDEO", "Có video", Kind.BOOLEAN, BOOLEAN_OPS, null, ALL_CATEGORIES, null, "listing_media", NULL_UNDECLARED,
                Role.FILTER, List.of()), Binding.PRESENCE, "listing_media", null, "t.media_type = 'VIDEO' AND t.active = true", null, null));
        row(fields, "custom_amenity.name", "Tiện ích tự khai báo", "listing_custom_amenities", "name", Kind.TEXT, null, null, null, null);
        row(fields, "viewing.day_of_week", "Ngày nhận xem phòng", "listing_viewing_days", "day_of_week", Kind.ENUM, null, DAYS, null, null);
        row(fields, "viewing.viewing_slot", "Buổi nhận xem phòng", "listing_viewing_slots", "viewing_slot", Kind.ENUM, null, SLOTS, null, null);
        // Computed, category independent.
        computed(fields, "occupancy.max_occupants", "Số người ở tối đa (mọi loại hình)", Kind.INTEGER, "người", null, List.of());
        computed(fields, "detail.bedroom_count", "Số phòng ngủ (nhà/căn hộ)", Kind.INTEGER, "phòng", null, List.of());
        computed(fields, "detail.bathroom_count", "Số phòng tắm/WC (nhà/căn hộ)", Kind.INTEGER, "phòng", null, List.of());
        computed(fields, "detail.furnishing_status", "Mức nội thất (mọi loại hình)", Kind.ENUM, null, FURNISHING_STATUS, List.of());
        computed(fields, "rent.monthly_amount", "Tiền thuê mỗi tháng theo số người ở", Kind.NUMBER, "VND/tháng", null, List.of("occupants"));
        computed(fields, "cost.fixed_monthly_total", "Tổng chi phí cố định mỗi tháng", Kind.NUMBER, "VND/tháng", null,
                List.of("occupants", "motorbikes", "cars"));
        computed(fields, "cost.estimated_monthly_total", "Tổng chi phí ước tính mỗi tháng (gồm điện nước theo mức dùng)", Kind.NUMBER,
                "VND/tháng", null, List.of("occupants", "motorbikes", "cars", "electricityKwh", "waterM3"));
        computed(fields, "deposit.amount_estimate", "Tiền cọc ước tính", Kind.NUMBER, "VND", null, List.of("occupants"));
        computed(fields, "initial.payment_estimate", "Khoản trả ban đầu (tháng đầu + cọc)", Kind.NUMBER, "VND", null, List.of("occupants"));
        put(fields, new Def(spec("display.highlights", "Điểm nổi bật", Kind.TEXT, List.of(), null, ALL_CATEGORIES, null, "application",
                "Chỉ để hiển thị.", Role.DISPLAY_ONLY, List.of()), Binding.NONE, null, null, null, null, null));
        return new Snapshot(hash(fields), Collections.unmodifiableMap(fields));
    }

    private static String safe(String code) {
        if (code == null || !code.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalStateException("Reference code is not a safe identifier: " + code);
        }
        return code;
    }

    private static FieldSpec spec(String code, String label, Kind kind, List<String> ops, String unit, List<String> categories,
                                  List<Choice> values, String source, String nullMeaning, Role role, List<String> context) {
        return new FieldSpec(code, label, kind, ops, unit, categories == null ? ALL_CATEGORIES : categories, values, source, nullMeaning, role, context);
    }

    private static List<String> ops(Kind kind) {
        return switch (kind) {
            case TEXT -> TEXT_OPS;
            case ENUM -> ENUM_OPS;
            case BOOLEAN -> BOOLEAN_OPS;
            default -> NUMBER_OPS;
        };
    }

    private static void put(Map<String, Def> fields, Def def) {
        if (fields.putIfAbsent(def.spec().code(), def) != null) throw new IllegalStateException("Duplicate AI field " + def.spec().code());
    }

    private static void listing(Map<String, Def> fields, String column, String label, Kind kind, String unit, List<Choice> values) {
        put(fields, new Def(spec(column, label, kind, ops(kind), unit, ALL_CATEGORIES, values, "listings", NULL_UNKNOWN, Role.FILTER, List.of()),
                Binding.LISTING, "listings", column, null, null, null));
    }

    private static void detail(Map<String, Def> fields, String code, String label, String table, String column, Kind kind, String unit,
                               List<Choice> values, List<String> categories) {
        put(fields, new Def(spec(code, label, kind, ops(kind), unit, categories, values, table, NULL_UNKNOWN, Role.FILTER, List.of()),
                Binding.DETAIL, table, column, null, null, null));
    }

    private static void scoped(Map<String, Def> fields, String scope, String table, String column, String label, Kind kind, String unit,
                               List<Choice> values) {
        detail(fields, scope + "." + column, label + " (" + SCOPE_LABELS.get(scope) + ")", table, column, kind, unit, values,
                List.of(scope.toUpperCase(Locale.ROOT)));
    }

    private static void row(Map<String, Def> fields, String code, String label, String table, String column, Kind kind, String unit,
                            List<Choice> values, String fixed, String rowKey) {
        put(fields, new Def(spec(code, label, kind, ops(kind), unit, ALL_CATEGORIES, values, table, NULL_UNDECLARED, Role.FILTER, List.of()),
                Binding.ROW, table, column, fixed, rowKey, null));
    }

    private static void computed(Map<String, Def> fields, String code, String label, Kind kind, String unit, List<Choice> values, List<String> context) {
        put(fields, new Def(spec(code, label, kind, ops(kind), unit, ALL_CATEGORIES, values, "computed",
                "Không tính được (thiếu dữ liệu tin hoặc thông tin của bạn) là chưa rõ: không khớp, không loại như sai.",
                Role.COMPUTED, context), Binding.SCALAR, null, null, null, null, null));
    }

    private static String hash(Map<String, Def> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Def def : fields.values()) {
                FieldSpec s = def.spec();
                digest.update((s.code() + "|" + s.kind() + "|" + s.role() + "|" + s.operators() + "|"
                        + (s.values() == null ? "" : s.values().stream().map(Choice::code).toList()) + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return "fields-v" + VERSION + "-" + HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
