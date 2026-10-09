package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.dto.response.AiListingSearchResponse;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/** Public-listing-only query tool. SQL identifiers come exclusively from this registry. */
@Service
public class AiListingSearchService {
    private enum Kind { TEXT, NUMBER, INTEGER, BOOLEAN, DATE }
    private record Field(String table, String column, Kind kind, String fixedCondition) {}

    private static final Map<String, Field> FIELDS = buildFields();
    private final NamedParameterJdbcTemplate jdbc;

    public AiListingSearchService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> fields() {
        Map<String, String> result = new LinkedHashMap<>();
        FIELDS.forEach((name, field) -> result.put(name, field.kind().name()));
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AiListingSearchResponse search(AiListingSearchRequest request) {
        if (request == null) throw new IllegalArgumentException("Search request is required");
        List<AiListingSearchRequest.Filter> filters = request.filters() == null ? List.of() : request.filters();
        if (filters.size() > 24) throw new IllegalArgumentException("Too many listing filters");
        int limit = request.limit() == null ? 5 : request.limit();
        if (limit < 1 || limit > 10) throw new IllegalArgumentException("Limit must be between 1 and 10");

        StringBuilder where = new StringBuilder("l.active = true AND l.status = 'PUBLISHED' AND (l.expires_at IS NULL OR l.expires_at > now())");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        for (int index = 0; index < filters.size(); index++) {
            AiListingSearchRequest.Filter filter = filters.get(index);
            if (filter == null || filter.field() == null || filter.op() == null) {
                throw new IllegalArgumentException("Invalid listing filter");
            }
            Field field = FIELDS.get(filter.field());
            if (field == null) throw new IllegalArgumentException("Unsupported listing field: " + filter.field());
            String op = filter.op().toLowerCase(Locale.ROOT);
            String operator = switch (op) {
                case "eq" -> "=";
                case "ne" -> "<>";
                case "gt" -> ">";
                case "gte" -> ">=";
                case "lt" -> "<";
                case "lte" -> "<=";
                case "contains" -> "LIKE";
                default -> throw new IllegalArgumentException("Unsupported listing operator: " + filter.op());
            };
            if ((field.kind() == Kind.BOOLEAN || field.kind() == Kind.TEXT) &&
                    !List.of("eq", "ne", "contains").contains(op)) {
                throw new IllegalArgumentException("Operator is not valid for " + filter.field());
            }
            if (op.equals("contains") && field.kind() != Kind.TEXT) {
                throw new IllegalArgumentException("contains requires a text field");
            }
            if (field.kind() == Kind.BOOLEAN && !List.of("eq", "ne").contains(op)) {
                throw new IllegalArgumentException("Boolean field requires eq or ne");
            }
            Object value = convert(field.kind(), filter.value());
            if (filter.field().equals("amenity.code")) {
                if (!List.of("eq", "ne").contains(op)) throw new IllegalArgumentException("amenity.code requires eq or ne");
                String key = "v" + index;
                params.addValue(key, value.toString().toUpperCase(Locale.ROOT));
                where.append(op.equals("eq") ? " AND EXISTS (" : " AND NOT EXISTS (")
                        .append("SELECT 1 FROM listing_amenities la JOIN amenities a ON a.id = la.amenity_id "
                                + "WHERE la.listing_id = l.id AND a.code = :").append(key).append(")");
                continue;
            }
            if (field.column() == null) {
                if (!List.of("eq", "ne").contains(op)) throw new IllegalArgumentException("Existence field requires eq or ne");
                boolean present = (Boolean) value;
                if (op.equals("ne")) present = !present;
                where.append(present ? " AND EXISTS (" : " AND NOT EXISTS (")
                        .append("SELECT 1 FROM ").append(field.table()).append(" t WHERE t.listing_id = l.id")
                        .append(" AND ").append(field.fixedCondition()).append(")");
                continue;
            }
            String key = "v" + index;
            String expression = field.table().equals("listings") ? "l." + field.column() : "t." + field.column();
            if (field.kind() == Kind.TEXT) {
                expression = "lower(" + expression + ")";
                value = ((String) value).toLowerCase(Locale.ROOT);
                if (op.equals("contains")) value = "%" + escapeLike((String) value) + "%";
            }
            params.addValue(key, value);
            String predicate = expression + " " + operator + " :" + key + (op.equals("contains") ? " ESCAPE '\\'" : "");
            if (field.table().equals("listings")) {
                where.append(" AND ").append(predicate);
            } else {
                where.append(" AND EXISTS (SELECT 1 FROM ").append(field.table()).append(" t WHERE t.listing_id = l.id");
                if (field.table().equals("addresses") || field.table().equals("listing_charges")) {
                    where.append(" AND t.active = true");
                }
                if (field.fixedCondition() != null) where.append(" AND ").append(field.fixedCondition());
                where.append(" AND ").append(predicate).append(")");
            }
        }
        String from = " FROM listings l WHERE " + where;
        long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        String order = switch (request.sort() == null ? "newest" : request.sort()) {
            case "price_asc" -> "l.price_amount ASC, l.id ASC";
            case "price_desc" -> "l.price_amount DESC, l.id ASC";
            case "area_asc" -> "l.area_m2 ASC, l.id ASC";
            case "area_desc" -> "l.area_m2 DESC, l.id ASC";
            case "newest" -> "l.published_at DESC, l.id ASC";
            default -> throw new IllegalArgumentException("Unsupported listing sort");
        };
        List<String> ids = jdbc.queryForList("SELECT l.id" + from + " ORDER BY " + order + " LIMIT :limit", params, String.class);
        return new AiListingSearchResponse(ids, total, Instant.now());
    }

    private static Object convert(Kind kind, Object value) {
        if (value == null) throw new IllegalArgumentException("Null filters are not supported");
        String text = value.toString().trim();
        if (text.isEmpty() || text.length() > 160) throw new IllegalArgumentException("Invalid filter value");
        try {
            return switch (kind) {
                case TEXT -> text;
                case NUMBER -> new BigDecimal(text);
                case INTEGER -> Integer.valueOf(text);
                case DATE -> Date.valueOf(LocalDate.parse(text));
                case BOOLEAN -> {
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false"))
                        throw new IllegalArgumentException("Boolean must be true or false");
                    yield Boolean.valueOf(text);
                }
            };
        } catch (NumberFormatException | java.time.format.DateTimeParseException error) {
            throw new IllegalArgumentException("Invalid filter value for " + kind, error);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Map<String, Field> buildFields() {
        Map<String, Field> fields = new LinkedHashMap<>();
        add(fields, "listings", null, Kind.TEXT, "id", "title", "description", "category", "price_unit", "currency", "deposit_type", "payment_cycle");
        add(fields, "listings", null, Kind.NUMBER, "price_amount", "area_m2", "deposit_amount");
        add(fields, "listings", null, Kind.INTEGER, "deposit_months", "minimum_lease_months", "max_motorbike_count", "max_car_count");
        add(fields, "listings", null, Kind.BOOLEAN, "negotiable", "management_fee_included", "vat_included");
        add(fields, "listings", null, Kind.DATE, "available_from", "published_at", "expires_at");
        add(fields, "listings", null, Kind.INTEGER, "view_count");
        add(fields, "addresses", null, Kind.TEXT, "province_code", "province_name", "ward_code", "ward_name", "street_line", "full_address");
        addScoped(fields, "house", "listing_house_details", Kind.NUMBER, "land_area_m2", "frontage_width_m", "length_m", "access_road_width_m");
        addScoped(fields, "house", "listing_house_details", Kind.INTEGER, "frontage_count", "total_floors", "bedroom_count", "bathroom_count", "living_room_count", "kitchen_count", "max_occupants", "max_vehicles", "rented_floor_from", "rented_floor_to");
        addScoped(fields, "house", "listing_house_details", Kind.BOOLEAN, "has_rooftop", "has_garage");
        addScoped(fields, "house", "listing_house_details", Kind.TEXT, "access_type", "furnishing_status", "legal_status", "rental_scope_description");
        addScoped(fields, "apartment", "listing_apartment_details", Kind.TEXT, "project_name", "building_block", "unit_code", "furnishing_status", "main_door_direction", "balcony_direction", "view_description", "legal_status");
        addScoped(fields, "apartment", "listing_apartment_details", Kind.INTEGER, "floor_number", "building_total_floors", "bedroom_count", "bathroom_count", "living_room_count", "kitchen_count", "max_occupants");
        addScoped(fields, "room", "listing_room_details", Kind.TEXT, "room_code", "restroom_type", "kitchen_type", "balcony_type", "furnishing_status", "access_type", "access_hours_type", "electric_meter_type", "water_meter_type", "parking_policy");
        addScoped(fields, "room", "listing_room_details", Kind.INTEGER, "floor_number", "max_occupants", "max_vehicles");
        addScoped(fields, "room", "listing_room_details", Kind.BOOLEAN, "has_window", "has_balcony", "has_mezzanine");
        for (String type : List.of("ELECTRICITY", "WATER", "MANAGEMENT", "INTERNET", "SERVICE_OR_GARBAGE", "MOTORBIKE_PARKING", "CAR_PARKING", "OVERTIME_AIR_CONDITIONING", "OTHER")) {
            String fixed = "t.charge_type = '" + type + "'";
            fields.put("charge." + type + ".amount", new Field("listing_charges", "amount", Kind.NUMBER, fixed));
            fields.put("charge." + type + ".included_in_rent", new Field("listing_charges", "included_in_rent", Kind.BOOLEAN, fixed));
            fields.put("charge." + type + ".billing_method", new Field("listing_charges", "billing_method", Kind.TEXT, fixed));
            fields.put("charge." + type + ".unit", new Field("listing_charges", "unit", Kind.TEXT, fixed));
            fields.put("charge." + type + ".description", new Field("listing_charges", "description", Kind.TEXT, fixed));
            fields.put("charge." + type + ".custom_name", new Field("listing_charges", "custom_name", Kind.TEXT, fixed));
        }
        fields.put("amenity.code", new Field("amenities", "code", Kind.TEXT, null));
        for (String code : List.of("WIFI", "AIR_CONDITIONER", "WATER_HEATER", "REFRIGERATOR", "WASHING_MACHINE", "ELEVATOR", "PARKING", "SECURITY_24_7", "CAMERA", "PETS_ALLOWED", "SWIMMING_POOL", "GYM")) {
            fields.put("amenity." + code, new Field("listing_amenities", null, Kind.BOOLEAN, "EXISTS (SELECT 1 FROM amenities a WHERE a.id = t.amenity_id AND a.code = '" + code + "')"));
        }
        for (String code : List.of("BED", "WARDROBE", "WORK_DESK", "KITCHEN_SHELF", "REFRIGERATOR", "WASHING_MACHINE", "WATER_HEATER", "CURTAIN", "FAN", "SOFA_SET", "DINING_SET", "TV", "KITCHEN_CABINET", "COOKTOP", "AIR_CONDITIONER", "LIGHTING")) {
            fields.put("furnishing." + code, new Field("listing_furnishing_assets", null, Kind.BOOLEAN, "t.item_code = '" + code + "'"));
            fields.put("furnishing." + code + ".quantity", new Field("listing_furnishing_assets", "quantity", Kind.INTEGER, "t.item_code = '" + code + "'"));
            fields.put("furnishing." + code + ".handover_condition", new Field("listing_furnishing_assets", "handover_condition", Kind.TEXT, "t.item_code = '" + code + "'"));
            fields.put("furnishing." + code + ".asset_name", new Field("listing_furnishing_assets", "asset_name", Kind.TEXT, "t.item_code = '" + code + "'"));
            fields.put("furnishing." + code + ".condition_note", new Field("listing_furnishing_assets", "condition_note", Kind.TEXT, "t.item_code = '" + code + "'"));
        }
        addScoped(fields, "furnishing", "listing_furnishing_assets", Kind.TEXT, "item_code", "asset_name");
        fields.put("media.IMAGE", new Field("listing_media", null, Kind.BOOLEAN, "t.media_type = 'IMAGE' AND t.active = true"));
        fields.put("media.VIDEO", new Field("listing_media", null, Kind.BOOLEAN, "t.media_type = 'VIDEO' AND t.active = true"));
        addScoped(fields, "custom_amenity", "listing_custom_amenities", Kind.TEXT, "name");
        addScoped(fields, "viewing", "listing_viewing_days", Kind.TEXT, "day_of_week");
        addScoped(fields, "viewing", "listing_viewing_slots", Kind.TEXT, "viewing_slot");
        return Map.copyOf(fields);
    }

    private static void add(Map<String, Field> fields, String table, String condition, Kind kind, String... columns) {
        for (String column : columns) fields.put(column, new Field(table, column, kind, condition));
    }

    private static void addScoped(Map<String, Field> fields, String scope, String table, Kind kind, String... columns) {
        for (String column : columns) fields.put(scope + "." + column, new Field(table, column, kind, null));
    }
}
