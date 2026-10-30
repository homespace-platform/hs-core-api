package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.dto.request.AiListingSearchRequest.Condition;
import com.hs.listing.dto.request.AiListingSearchRequest.NearbyCriteria;
import com.hs.listing.dto.request.AiListingSearchRequest.SearchContext;
import com.hs.listing.dto.response.AiListingSearchResponse;
import com.hs.listing.service.AiListingFieldCatalog.Binding;
import com.hs.listing.service.AiListingFieldCatalog.Choice;
import com.hs.listing.service.AiListingFieldCatalog.Def;
import com.hs.listing.service.AiListingFieldCatalog.Kind;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public-listing-only query tool. SQL identifiers come exclusively from {@link AiListingFieldCatalog}; values are
 * always bound. Every predicate, including nearby and computed costs, is applied before count and LIMIT.
 * Conditions use SQL three-valued logic: a listing whose data cannot decide a condition is UNKNOWN and is never
 * returned as a match.
 */
@Service
public class AiListingSearchService {
    private static final int MAX_FILTERS = 24, MAX_LEAVES = 40, MAX_DEPTH = 4, MAX_IDS = 50, MAX_NEARBY = 3, MAX_IN = 20;
    private static final String PUBLIC = "l.active = true AND l.status = 'PUBLISHED' AND (l.expires_at IS NULL OR l.expires_at > now())";

    private final NamedParameterJdbcTemplate jdbc;
    private final int maxResults;
    private final AiListingFieldCatalog catalog;
    @Autowired(required = false)
    private com.hs.listing.nearby.NearbyQueryService nearbyQuery;

    public AiListingSearchService(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, 20);
    }

    public AiListingSearchService(NamedParameterJdbcTemplate jdbc, int maxResults) {
        this(jdbc, maxResults, AiListingFieldCatalog.seeded());
    }

    @Autowired
    public AiListingSearchService(NamedParameterJdbcTemplate jdbc, @Value("${listing.ai.max-results:20}") int maxResults,
                                  AiListingFieldCatalog catalog) {
        if (maxResults < 1 || maxResults > 50) throw new IllegalArgumentException("listing.ai.max-results must be between 1 and 50");
        this.jdbc = jdbc;
        this.maxResults = maxResults;
        this.catalog = Objects.requireNonNull(catalog);
    }

    void setNearbyQuery(com.hs.listing.nearby.NearbyQueryService nearbyQuery) {
        this.nearbyQuery = nearbyQuery;
    }

    public Map<String, String> fields() {
        return catalog.legacyFields();
    }

    public AiListingFieldCatalog.CatalogView catalog() {
        List<Map<String, String>> kinds = nearbyQuery == null || !nearbyQuery.enabled() ? List.of()
                : nearbyQuery.kinds().stream().map(k -> {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("code", k.code());
                    item.put("group", k.group().name());
                    item.put("label", k.label());
                    if (k.note() != null) item.put("note", k.note());
                    return item;
                }).toList();
        return catalog.view(kinds);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AiListingSearchResponse search(AiListingSearchRequest request) {
        if (request == null) throw new IllegalArgumentException("Search request is required");
        boolean v2 = request.version() != null;
        if (v2 && request.version() != AiListingFieldCatalog.VERSION) {
            throw new AiSearchValidationException("UNSUPPORTED_VERSION", "version", List.of("2"), "Unsupported AI search contract version");
        }
        List<AiListingSearchRequest.Filter> filters = request.filters() == null ? List.of() : request.filters();
        if (filters.size() > MAX_FILTERS) throw new AiSearchValidationException("TOO_COMPLEX", "filters", "Too many listing filters");
        int limit = request.limit() == null ? Math.min(5, maxResults) : request.limit();
        if (limit < 1 || limit > maxResults) {
            throw new AiSearchValidationException("INVALID_LIMIT", "limit", "Limit must be between 1 and " + maxResults);
        }
        String sort = request.sort() == null ? "newest" : request.sort();
        if (!AiListingFieldCatalog.SORTS.contains(sort)) {
            throw new AiSearchValidationException("INVALID_SORT", "sort", AiListingFieldCatalog.SORTS, "Unsupported listing sort");
        }

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        SearchContext context = validContext(request.context());
        params.addValue("ctxOccupants", context.occupants(), Types.INTEGER)
                .addValue("ctxMotorbikes", context.motorbikes(), Types.INTEGER)
                .addValue("ctxCars", context.cars(), Types.INTEGER)
                .addValue("ctxElectricityKwh", context.electricityKwh(), Types.INTEGER)
                .addValue("ctxWaterM3", context.waterM3(), Types.INTEGER);
        Compiler compiler = new Compiler(catalog.snapshot().fields(), params);

        List<Condition> roots = new ArrayList<>();
        for (AiListingSearchRequest.Filter filter : filters) {
            if (filter == null) throw new AiSearchValidationException("INVALID_FILTER", null, "Invalid listing filter");
            roots.add(Condition.leaf(filter.field(), filter.op(), filter.value()));
        }
        if (request.where() != null) {
            Condition where = request.where();
            if (where.all() != null && where.field() == null && where.any() == null) roots.addAll(where.all());
            else roots.add(where);
        }

        StringBuilder base = new StringBuilder(PUBLIC);
        List<String> pieces = new ArrayList<>();
        List<NearbyCriteria> nearby = new ArrayList<>();
        if (request.nearby() != null) nearby.add(request.nearby());
        if (request.nearbyAll() != null) nearby.addAll(request.nearbyAll());
        if (nearby.size() > MAX_NEARBY) throw new AiSearchValidationException("TOO_COMPLEX", "nearbyAll", "Too many nearby conditions");
        String targetDistance = null;
        for (int index = 0; index < nearby.size(); index++) {
            NearbyCriteria criteria = nearby.get(index);
            if (criteria == null) throw new AiSearchValidationException("INVALID_NEARBY", "nearby", "Invalid nearby condition");
            if (nearbyQuery == null) throw new AiSearchValidationException("NEARBY_UNAVAILABLE", "nearby", "Nearby query is unavailable");
            String suffix = "N" + index;
            try {
                if (criteria.placeId() != null) {
                    if (criteria.group() != null || criteria.kind() != null) {
                        throw new AiSearchValidationException("INVALID_NEARBY", "nearby", "Use either a named place or a nearby category");
                    }
                    var target = nearbyQuery.requireTarget(criteria.placeId());
                    String predicate = nearbyQuery.targetPredicate(target, criteria.radiusMeters(), params, suffix);
                    String distance = com.hs.listing.nearby.NearbyQueryService.distanceSql("targetLat" + suffix, "targetLon" + suffix);
                    if (targetDistance == null) targetDistance = distance;
                    pieces.add(compiler.unit("nearby.target:" + target.id(), predicate, distance));
            } else {
                    String predicate = nearbyQuery.groupPredicate(criteria.group(), criteria.kind(), criteria.radiusMeters(), params, suffix);
                    pieces.add(compiler.unit("nearby." + (criteria.kind() != null ? criteria.kind() : criteria.group()), predicate, null));
                }
            } catch (AiSearchValidationException error) {
                throw error;
            } catch (IllegalArgumentException error) {
                String reason = criteria.placeId() != null ? "TARGET_UNAVAILABLE" : "NEARBY_UNAVAILABLE";
                throw new AiSearchValidationException(reason, "nearby", error.getMessage());
            }
        }
        if (!roots.isEmpty()) pieces.add(compiler.group(roots, true, 1));
        if ("target_distance".equals(sort) && targetDistance == null) {
            throw new AiSearchValidationException("INVALID_SORT", "sort", "target_distance requires a named nearby place");
        }

        appendIds(base, params, request.scopeIds(), "scopeIds", "l.id IN (:scopeIds)");
        appendIds(base, params, request.excludeIds(), "excludeIds", "l.id NOT IN (:excludeIds)");
        if (request.keyword() != null && !request.keyword().isBlank()) {
            String keyword = request.keyword().trim();
            if (keyword.length() > 160) throw new AiSearchValidationException("INVALID_VALUE", "keyword", "Keyword is too long");
            params.addValue("kw", "%" + escapeLike(keyword.toLowerCase(Locale.ROOT)) + "%");
            base.append(" AND (lower(l.title) LIKE :kw ESCAPE '\\' OR lower(coalesce(l.description,'')) LIKE :kw ESCAPE '\\'"
                    + " OR EXISTS (SELECT 1 FROM listing_addresses ka WHERE ka.listing_id = l.id AND ka.active = true AND ("
                    + "lower(coalesce(ka.display,'')) LIKE :kw ESCAPE '\\' OR lower(coalesce(ka.street,'')) LIKE :kw ESCAPE '\\'"
                    + " OR lower(coalesce(ka.ward,'')) LIKE :kw ESCAPE '\\' OR lower(coalesce(ka.city,'')) LIKE :kw ESCAPE '\\')))");
        }
        String tree = pieces.isEmpty() ? null : String.join(" AND ", pieces);
        String from = " FROM listings l WHERE " + base + (tree == null ? "" : " AND " + tree);
        long total = jdbc.queryForObject("SELECT count(*)" + from, params, Long.class);
        String order = switch (sort) {
            case "price_asc" -> "l.price_amount ASC, l.id ASC";
            case "price_desc" -> "l.price_amount DESC, l.id ASC";
            case "area_asc" -> "l.area_m2 ASC, l.id ASC";
            case "area_desc" -> "l.area_m2 DESC, l.id ASC";
            case "rent_asc" -> Computed.RENT + " ASC NULLS LAST, l.id ASC";
            case "fixed_cost_asc" -> Computed.FIXED_TOTAL + " ASC NULLS LAST, l.id ASC";
            case "estimated_cost_asc" -> Computed.ESTIMATED_TOTAL + " ASC NULLS LAST, l.id ASC";
            case "target_distance" -> targetDistance + " ASC NULLS LAST, l.id ASC";
            default -> "l.published_at DESC, l.id ASC";
        };
        List<String> ids = jdbc.queryForList("SELECT l.id" + from + " ORDER BY " + order + " LIMIT :limit", params, String.class);
        if (!v2) return new AiListingSearchResponse(ids, total, Instant.now());

        Long unknown = tree == null ? 0L : jdbc.queryForObject(
                "SELECT count(*) FROM listings l WHERE " + base + " AND (" + tree + ") IS NULL", params, Long.class);
        List<AiListingSearchResponse.ListingEvidence> evidence = Boolean.FALSE.equals(request.evidence()) || ids.isEmpty()
                ? List.of() : evidence(ids, compiler.units, targetDistance, params);
        // Pagination excludes previously shown IDs but must not shrink the all-matching count a second time.
        // This clause is a fixed server-owned SQL fragment; user input is never SQL text.
        long matchedTotal = request.excludeIds() == null || request.excludeIds().isEmpty() ? total : jdbc.queryForObject(
                "SELECT count(*)" + from.replace(" AND l.id NOT IN (:excludeIds)", ""), params, Long.class);
        return new AiListingSearchResponse(ids, total, Instant.now(), AiListingFieldCatalog.VERSION, catalog.version(),
                unknown, compiler.missingContext(context), evidence, matchedTotal);
    }

    private List<AiListingSearchResponse.ListingEvidence> evidence(List<String> ids, List<Unit> units, String distance,
                                                                   MapSqlParameterSource params) {
        StringBuilder select = new StringBuilder("SELECT l.id");
        if (distance != null) select.append(", ").append(distance).append(" AS target_distance");
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            select.append(", CASE WHEN (").append(unit.sql()).append(") THEN 'MATCH' WHEN NOT (").append(unit.sql())
                    .append(") THEN 'NOT_MATCH' ELSE 'UNKNOWN' END AS s").append(i);
            if (unit.actual() != null) select.append(", ").append(unit.actual()).append(" AS a").append(i);
        }
        params.addValue("evidenceIds", ids);
        Map<String, AiListingSearchResponse.ListingEvidence> rows = new HashMap<>();
        jdbc.query(select + " FROM listings l WHERE l.id IN (:evidenceIds)", params, (java.sql.ResultSet rs) -> {
            while (rs.next()) {
                List<AiListingSearchResponse.Check> checks = new ArrayList<>();
                for (int i = 0; i < units.size(); i++) {
                    Unit unit = units.get(i);
                    checks.add(new AiListingSearchResponse.Check(unit.fields(), rs.getString("s" + i),
                            unit.actual() == null ? null : rs.getObject("a" + i)));
                }
                Double meters = null;
                if (distance != null) {
                    double value = rs.getDouble("target_distance");
                    meters = rs.wasNull() ? null : Math.round(value * 10) / 10.0;
                }
                rows.put(rs.getString("id"), new AiListingSearchResponse.ListingEvidence(rs.getString("id"), meters, checks));
            }
            return null;
        });
        return ids.stream().map(rows::get).filter(Objects::nonNull).toList();
    }

    private static SearchContext validContext(SearchContext context) {
        if (context == null) return new SearchContext(null, null, null, null, null);
        range(context.occupants(), 1, 20, "context.occupants");
        range(context.motorbikes(), 0, 10, "context.motorbikes");
        range(context.cars(), 0, 5, "context.cars");
        range(context.electricityKwh(), 0, 5000, "context.electricityKwh");
        range(context.waterM3(), 0, 500, "context.waterM3");
        return context;
    }

    private static void range(Integer value, int min, int max, String field) {
        if (value != null && (value < min || value > max)) {
            throw new AiSearchValidationException("INVALID_CONTEXT", field, field + " must be between " + min + " and " + max);
        }
    }

    private static void appendIds(StringBuilder where, MapSqlParameterSource params, List<String> ids, String name, String predicate) {
        if (ids == null) return;
        if (ids.isEmpty() && name.equals("scopeIds")) {
            throw new AiSearchValidationException("INVALID_SCOPE", name, "An explicit scope cannot be empty");
        }
        if (ids.isEmpty()) return;
        if (ids.size() > MAX_IDS) throw new AiSearchValidationException("INVALID_SCOPE", name, "Too many listing IDs");
        List<String> safe = new ArrayList<>();
        for (String id : ids) {
            try {
                safe.add(UUID.fromString(id).toString());
            } catch (RuntimeException invalid) {
                throw new AiSearchValidationException("INVALID_SCOPE", name, "Invalid listing ID");
            }
        }
        params.addValue(name, safe);
        where.append(" AND ").append(predicate);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    record Unit(String fields, String sql, String actual) {}

    /** Computed expressions mirror {@link RentalCostCalculator}; anything it cannot know stays NULL. */
    static final class Computed {
        private Computed() {}
        static final String RENT = "(CASE WHEN l.price_amount IS NULL THEN NULL WHEN l.price_unit IN ('MONTH','ROOM_MONTH') THEN l.price_amount"
                + " WHEN l.price_unit = 'PERSON_MONTH' THEN l.price_amount * CAST(:ctxOccupants AS integer) ELSE NULL END)";
        static String byCategory(String column) {
            return "(CASE l.category WHEN 'ROOM' THEN (SELECT d.COL FROM listing_room_details d WHERE d.listing_id = l.id)"
                    .replace("COL", column)
                    + " WHEN 'HOUSE' THEN (SELECT d." + column + " FROM listing_house_details d WHERE d.listing_id = l.id)"
                    + " WHEN 'APARTMENT' THEN (SELECT d." + column + " FROM listing_apartment_details d WHERE d.listing_id = l.id) END)";
        }
        static String homeOnly(String column) {
            return "(CASE l.category WHEN 'HOUSE' THEN (SELECT d." + column + " FROM listing_house_details d WHERE d.listing_id = l.id)"
                    + " WHEN 'APARTMENT' THEN (SELECT d." + column + " FROM listing_apartment_details d WHERE d.listing_id = l.id) END)";
        }
        private static final String VEHICLES = "(CASE t.charge_type WHEN 'MOTORBIKE_PARKING' THEN CAST(:ctxMotorbikes AS integer) ELSE CAST(:ctxCars AS integer) END)";
        private static final String ROOM_PARKING_NONE = "(l.category = 'ROOM' AND EXISTS (SELECT 1 FROM listing_room_details pr WHERE pr.listing_id = l.id AND pr.parking_policy = 'NONE'))";
        private static final String FIXED_ROW = "CASE"
                + " WHEN t.charge_type = 'MANAGEMENT' AND l.category IN ('ROOM','HOUSE') THEN 0"
                + " WHEN t.included_in_rent OR t.billing_method IN ('INCLUDED','FREE','NOT_APPLICABLE') THEN 0"
                + " WHEN t.billing_method IN ('PER_KWH','PER_M3','STATE_WATER_RATE','PER_HOUR') THEN 0"
                + " WHEN t.charge_type IN ('MOTORBIKE_PARKING','CAR_PARKING') THEN CASE WHEN " + ROOM_PARKING_NONE + " THEN 0"
                + " WHEN " + VEHICLES + " IS NULL THEN NULL WHEN " + VEHICLES + " = 0 THEN 0 WHEN t.amount IS NULL THEN NULL"
                + " WHEN t.billing_method = 'PER_VEHICLE_MONTH' THEN t.amount * " + VEHICLES
                + " WHEN t.billing_method = 'PER_MONTH' THEN t.amount ELSE NULL END"
                + " WHEN t.amount IS NULL THEN NULL"
                + " WHEN t.billing_method = 'PER_MONTH' THEN t.amount"
                + " WHEN t.billing_method = 'PER_PERSON_MONTH' THEN t.amount * CAST(:ctxOccupants AS integer)"
                + " WHEN t.billing_method = 'PER_M2_MONTH' THEN t.amount * l.area_m2"
                + " ELSE NULL END";
        private static final String USAGE_ROW = "CASE"
                + " WHEN t.charge_type = 'MANAGEMENT' AND l.category IN ('ROOM','HOUSE') THEN 0"
                + " WHEN t.included_in_rent OR t.billing_method IN ('INCLUDED','FREE','NOT_APPLICABLE') THEN 0"
                + " WHEN t.billing_method = 'PER_KWH' THEN t.amount * CAST(:ctxElectricityKwh AS integer)"
                + " WHEN t.billing_method = 'PER_M3' THEN t.amount * CAST(:ctxWaterM3 AS integer)"
                + " WHEN t.billing_method = 'STATE_WATER_RATE' THEN NULL"
                + " ELSE 0 END";
        private static String sum(String row) {
            return "(SELECT CASE WHEN bool_or(x.v IS NULL) THEN NULL ELSE coalesce(sum(x.v), 0) END FROM (SELECT " + row
                    + " AS v FROM listing_charges t WHERE t.listing_id = l.id AND t.active = true) x)";
        }
        static final String FIXED_TOTAL = "(" + RENT + " + " + sum(FIXED_ROW) + ")";
        static final String ESTIMATED_TOTAL = "(" + RENT + " + " + sum(FIXED_ROW) + " + " + sum(USAGE_ROW) + ")";
        static final String DEPOSIT = "(CASE l.deposit_type WHEN 'NONE' THEN 0 WHEN 'FIXED_AMOUNT' THEN l.deposit_amount"
                + " WHEN 'MONTH_COUNT' THEN " + RENT + " * l.deposit_months ELSE NULL END)";
        static final String INITIAL = "(" + RENT + " + " + DEPOSIT + ")";

        static String of(String code) {
            return switch (code) {
                case "occupancy.max_occupants" -> byCategory("max_occupants");
                case "detail.bedroom_count" -> homeOnly("bedroom_count");
                case "detail.bathroom_count" -> homeOnly("bathroom_count");
                case "detail.furnishing_status" -> byCategory("furnishing_status");
                case "rent.monthly_amount" -> RENT;
                case "cost.fixed_monthly_total" -> FIXED_TOTAL;
                case "cost.estimated_monthly_total" -> ESTIMATED_TOTAL;
                case "deposit.amount_estimate" -> DEPOSIT;
                case "initial.payment_estimate" -> INITIAL;
                default -> throw new IllegalStateException("No computed expression for " + code);
            };
        }
    }

    /** One compiled leaf. Row predicates with the same row key are evaluated against one child row. */
    private record Piece(String sql, Def def, String rowPredicate, String aggregatePredicate, String code) {}

    private final class Compiler {
        private final Map<String, Def> fields;
        private final MapSqlParameterSource params;
        private final List<Unit> units = new ArrayList<>();
        private final Set<String> context = new LinkedHashSet<>();
        private int leaves;
        private int next;

        Compiler(Map<String, Def> fields, MapSqlParameterSource params) {
            this.fields = fields;
            this.params = params;
        }

        String unit(String label, String sql, String actual) {
            if (units.size() < MAX_LEAVES) units.add(new Unit(label, sql, actual));
            return "(" + sql + ")";
        }

        List<String> missingContext(SearchContext supplied) {
            List<String> missing = new ArrayList<>();
            for (String name : context) {
                Integer value = switch (name) {
                    case "occupants" -> supplied.occupants();
                    case "motorbikes" -> supplied.motorbikes();
                    case "cars" -> supplied.cars();
                    case "electricityKwh" -> supplied.electricityKwh();
                    default -> supplied.waterM3();
                };
                if (value == null) missing.add(name);
            }
            return missing;
        }

        String compile(Condition condition, int depth) {
            if (condition == null) throw new AiSearchValidationException("INVALID_FILTER", null, "Invalid listing condition");
            if (depth > MAX_DEPTH) throw new AiSearchValidationException("TOO_COMPLEX", null, "Condition tree is too deep");
            boolean leaf = condition.field() != null || condition.op() != null;
            int shapes = (leaf ? 1 : 0) + (condition.all() != null ? 1 : 0) + (condition.any() != null ? 1 : 0);
            if (shapes != 1) throw new AiSearchValidationException("INVALID_FILTER", condition.field(), "A condition is exactly one of field, all or any");
            if (condition.all() != null) return group(condition.all(), true, depth + 1);
            if (condition.any() != null) return group(condition.any(), false, depth + 1);
            Piece piece = leaf(condition);
            return piece.sql() != null ? unit(piece.code(), piece.sql(), actual(piece)) : standalone(piece);
        }

        String group(List<Condition> items, boolean and, int depth) {
            if (items == null || items.isEmpty()) throw new AiSearchValidationException("INVALID_FILTER", null, "A condition group cannot be empty");
            if (depth > MAX_DEPTH) throw new AiSearchValidationException("TOO_COMPLEX", null, "Condition tree is too deep");
            List<String> parts = new ArrayList<>();
            Map<String, List<Piece>> rows = new LinkedHashMap<>();
            Map<String, Integer> rowPositions = new HashMap<>();
            for (Condition item : items) {
                if (item != null && item.field() != null && item.all() == null && item.any() == null) {
                    Piece piece = leaf(item);
                    if (and && piece.sql() == null && piece.def().rowKey() != null) {
                        rows.computeIfAbsent(piece.def().rowKey(), key -> {
                            rowPositions.put(key, parts.size());
                            parts.add(null);
                            return new ArrayList<>();
                        }).add(piece);
                    } else {
                        parts.add(piece.sql() != null ? unit(piece.code(), piece.sql(), actual(piece)) : standalone(piece));
                    }
                } else {
                    parts.add(compile(item, depth));
                }
            }
            rows.forEach((key, pieces) -> parts.set(rowPositions.get(key), rowGroup(pieces)));
            return parts.size() == 1 ? parts.getFirst() : "(" + String.join(and ? " AND " : " OR ", parts) + ")";
        }

        private String actual(Piece piece) {
            Binding binding = piece.def().binding();
            if (binding == Binding.SCALAR) return Computed.of(piece.def().spec().code());
            return null;
        }

        private String rowWhere(Def def, List<String> predicates) {
            StringBuilder where = new StringBuilder("t.listing_id = l.id");
            if (def.table().equals("listing_charges") || def.table().equals("listing_addresses")) where.append(" AND t.active = true");
            if (def.fixed() != null) where.append(" AND ").append(def.fixed());
            predicates.forEach(p -> where.append(" AND ").append(p));
            return where.toString();
        }

        private String standalone(Piece piece) {
            return rowGroup(List.of(piece));
        }

        private String rowGroup(List<Piece> pieces) {
            Def def = pieces.getFirst().def();
            List<String> predicates = pieces.stream().map(Piece::rowPredicate).filter(Objects::nonNull).toList();
            List<String> aggregates = pieces.stream().map(Piece::aggregatePredicate).filter(Objects::nonNull).toList();
            String where = rowWhere(def, predicates);
            String sql;
            if (aggregates.isEmpty()) {
                sql = "EXISTS (SELECT 1 FROM " + def.table() + " t WHERE " + where + ")";
            } else {
                String sum = "(SELECT coalesce(sum(t.quantity), 0) FROM " + def.table() + " t WHERE " + where + ")";
                sql = aggregates.stream().map(a -> a.replace("SUM_EXPR", sum)).reduce((a, b) -> a + " AND " + b).orElseThrow();
            }
            String label = String.join(" + ", pieces.stream().map(Piece::code).distinct().toList());
            return unit(label, sql, null);
        }

        private Piece leaf(Condition condition) {
            if (++leaves > MAX_LEAVES) throw new AiSearchValidationException("TOO_COMPLEX", null, "Too many listing conditions");
            String code = condition.field();
            if (code == null || condition.op() == null) throw new AiSearchValidationException("INVALID_FILTER", code, "Invalid listing filter");
            Def def = fields.get(code);
            if (def == null) throw new AiSearchValidationException("UNKNOWN_FIELD", code, "Unsupported listing field: " + code);
            if (def.binding() == Binding.NONE) {
                throw new AiSearchValidationException("NOT_FILTERABLE", code, "Field is display-only: " + code);
            }
            String op = condition.op().toLowerCase(Locale.ROOT);
            if (!def.spec().operators().contains(op)) {
                throw new AiSearchValidationException("UNSUPPORTED_OPERATOR", code, def.spec().operators(),
                        "Operator " + condition.op() + " is not valid for " + code);
            }
            context.addAll(def.spec().requiresContext());
            String key = "v" + next++;
            return switch (def.binding()) {
                case LISTING -> new Piece(compare("l." + def.column(), def, op, condition.value(), key), def, null, null, code);
                case DETAIL -> {
                    String where = "t.listing_id = l.id" + (def.table().equals("listing_addresses") ? " AND t.active = true" : "");
                    yield new Piece("CASE WHEN EXISTS (SELECT 1 FROM " + def.table() + " t WHERE " + where + ") THEN (SELECT "
                            + compare("t." + def.column(), def, op, condition.value(), key) + " FROM " + def.table() + " t WHERE "
                            + where + ") ELSE false END", def, null, null, code);
                }
                case SCALAR -> new Piece(compare(Computed.of(code), def, op, condition.value(), key), def, null, null, code);
                case ROW -> {
                    String expression = def.column().startsWith("CASE ") ? def.column() : "t." + def.column();
                    yield new Piece(null, def, compare(expression, def, op, condition.value(), key), null, code);
                }
                case AGGREGATE -> new Piece(null, def, null, compare("SUM_EXPR", def, op, condition.value(), key), code);
                case PRESENCE -> {
                    boolean present = (Boolean) convert(Kind.BOOLEAN, code, condition.value());
                    if (op.equals("ne")) present = !present;
                    if (present && def.rowKey() != null) yield new Piece(null, def, null, null, code);
                    String sql = (present ? "EXISTS" : "NOT EXISTS") + " (SELECT 1 FROM " + def.table() + " t WHERE " + rowWhere(def, List.of()) + ")";
                    yield new Piece(sql, def, null, null, code);
                }
                case AMENITY_CODE -> {
                    String value = enumValue(def, code, condition.value());
                    params.addValue(key, value);
                    yield new Piece((op.equals("eq") ? "EXISTS" : "NOT EXISTS") + " (SELECT 1 FROM listing_amenities la JOIN amenities a ON a.id = la.amenity_id "
                            + "WHERE la.listing_id = l.id AND a.code = :" + key + ")", def, null, null, code);
                }
                case NONE -> throw new IllegalStateException();
            };
        }

        private String compare(String expression, Def def, String op, Object raw, String key) {
            Kind kind = def.spec().kind();
            String code = def.spec().code();
            if (kind == Kind.ENUM) {
                List<String> values;
                if (op.equals("in")) {
                    values = listValue(code, raw).stream().map(v -> enumValue(def, code, v)).distinct().toList();
                } else if (Set.of("gte", "gt", "lte", "lt").contains(op)) {
                    List<String> order = def.ordinal();
                    int rank = order.indexOf(enumValue(def, code, raw));
                    values = switch (op) {
                        case "gte" -> order.subList(0, rank + 1);
                        case "gt" -> order.subList(0, rank);
                        case "lte" -> order.subList(rank, order.size());
                        default -> order.subList(rank + 1, order.size());
                    };
                    if (values.isEmpty()) return "false";
                } else {
                    params.addValue(key, enumValue(def, code, raw));
                    return expression + (op.equals("eq") ? " = :" : " <> :") + key;
                }
                params.addValue(key, values);
                return expression + " IN (:" + key + ")";
            }
            Object value = convert(kind, code, raw);
            if (kind == Kind.TEXT) {
                String text = ((String) value).toLowerCase(Locale.ROOT);
                if (op.equals("contains")) {
                    params.addValue(key, "%" + escapeLike(text) + "%");
                    return "lower(" + expression + ") LIKE :" + key + " ESCAPE '\\'";
                }
                params.addValue(key, text);
                return "lower(" + expression + ") " + (op.equals("eq") ? "=" : "<>") + " :" + key;
            }
            params.addValue(key, value);
            String operator = switch (op) {
                case "eq" -> "=";
                case "ne" -> "<>";
                case "gt" -> ">";
                case "gte" -> ">=";
                case "lt" -> "<";
                case "lte" -> "<=";
                default -> throw new AiSearchValidationException("UNSUPPORTED_OPERATOR", code, def.spec().operators(), "Unsupported listing operator: " + op);
            };
            return expression + " " + operator + " :" + key;
        }

        private String enumValue(Def def, String code, Object raw) {
            if (raw == null) throw new AiSearchValidationException("INVALID_VALUE", code, "Null filters are not supported");
            String value = raw.toString().trim().toUpperCase(Locale.ROOT);
            List<String> allowed = def.spec().values() == null ? List.of() : def.spec().values().stream().map(Choice::code).toList();
            if (!allowed.contains(value)) {
                throw new AiSearchValidationException("INVALID_VALUE", code, allowed, "Value is not allowed for " + code);
            }
            return value;
        }

        private List<Object> listValue(String code, Object raw) {
            List<Object> values = raw instanceof Collection<?> collection ? new ArrayList<>(collection)
                    : raw == null ? List.of() : new ArrayList<>(Arrays.asList(raw.toString().split(",")));
            if (values.isEmpty() || values.size() > MAX_IN) {
                throw new AiSearchValidationException("INVALID_VALUE", code, "in requires 1 to " + MAX_IN + " values");
            }
            return values;
        }
    }

    private static Object convert(Kind kind, String field, Object value) {
        if (value == null) throw new AiSearchValidationException("INVALID_VALUE", field, "Null filters are not supported");
        String text = value.toString().trim();
        if (text.isEmpty() || text.length() > 160) throw new AiSearchValidationException("INVALID_VALUE", field, "Invalid filter value");
        try {
            return switch (kind) {
                case TEXT, ENUM -> text;
                case NUMBER -> new BigDecimal(text);
                case INTEGER -> Integer.valueOf(text);
                case DATE -> Date.valueOf(LocalDate.parse(text));
                case BOOLEAN -> {
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                        throw new AiSearchValidationException("INVALID_VALUE", field, List.of("true", "false"), "Boolean must be true or false");
                    }
                    yield Boolean.valueOf(text);
                }
            };
        } catch (NumberFormatException | java.time.format.DateTimeParseException error) {
            throw new AiSearchValidationException("INVALID_VALUE", field, "Invalid filter value for " + kind);
        }
    }
}
