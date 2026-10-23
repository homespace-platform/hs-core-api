package com.hs.listing.service.surroundings;

import com.hs.listing.service.surroundings.SurroundingsDtos.*;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Compares the amenities around a bounded candidate set of published listings. Coordinates always come from
 * {@code listing_addresses}; the recommendation cap is enforced here, never by the model.
 */
@Service
public class SurroundingsService {
    private static final Pattern UUID_LIKE = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final int MAX_CATEGORY_GROUPS = 10;
    private static final int WEIGHT_TARGET = 5, WEIGHT_PRIORITY = 3, WEIGHT_PRESET = 1;
    private static final Set<String> GENERIC_TARGET_WORDS = Set.of("co", "so", "cs", "truong", "dai", "hoc", "dh", "tai", "o", "cua", "va", "la", "cho");
    private static final List<PoiCategory> DEFAULT_GROUPS = List.of(
            PoiCategory.SUPERMARKET, PoiCategory.PUBLIC_TRANSPORT, PoiCategory.CONVENIENCE_STORE, PoiCategory.PHARMACY, PoiCategory.FOOD);
    private static final List<PoiCategory> EDUCATION = List.of(PoiCategory.UNIVERSITY, PoiCategory.SCHOOL, PoiCategory.KINDERGARTEN);

    record Row(String id, boolean available, Double latitude, Double longitude) {}

    private final NamedParameterJdbcTemplate jdbc;
    private final PoiFetcher fetcher;
    private final int defaultRadius, maxRadius, maxCandidates, recommendationLimit, maxPages, maxRequestsPerTurn, deadlineSeconds;

    @Autowired
    public SurroundingsService(NamedParameterJdbcTemplate jdbc, PoiFetcher fetcher,
                               @Value("${geoapify.default-radius-meters:3000}") int defaultRadius,
                               @Value("${geoapify.max-radius-meters:10000}") int maxRadius,
                               @Value("${recommendation.max-candidates:10}") int maxCandidates,
                               @Value("${recommendation.max-results:3}") int recommendationLimit,
                               @Value("${geoapify.max-pages:2}") int maxPages,
                               @Value("${geoapify.max-requests-per-turn:24}") int maxRequestsPerTurn,
                               @Value("${geoapify.turn-deadline-seconds:25}") int deadlineSeconds) {
        this.jdbc = jdbc;
        this.fetcher = fetcher;
        this.defaultRadius = defaultRadius;
        this.maxRadius = Math.max(maxRadius, defaultRadius);
        this.maxCandidates = Math.min(Math.max(maxCandidates, 1), 20);
        this.recommendationLimit = Math.min(Math.max(recommendationLimit, 1), 3); // product rule: never more than 3
        this.maxPages = Math.max(1, maxPages);
        this.maxRequestsPerTurn = Math.max(1, maxRequestsPerTurn);
        this.deadlineSeconds = Math.max(5, deadlineSeconds);
    }

    // ---------------------------------------------------------------- request validation

    private record Resolved(List<String> ids, List<RenterProfile> profiles, List<PoiCategory> priorities, int radius, String target,
                            Map<String, Integer> positions) {}

    private Resolved validate(Request request) {
        if (request == null || request.listingIds() == null || request.listingIds().isEmpty())
            throw new IllegalArgumentException("listingIds is required");
        if (request.listingIds().size() > maxCandidates)
            throw new IllegalArgumentException("At most " + maxCandidates + " candidate listings can be compared per request");
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String id : request.listingIds()) {
            if (id == null || !UUID_LIKE.matcher(id.strip()).matches()) throw new IllegalArgumentException("Invalid listing ID");
            ids.add(id.strip().toLowerCase(Locale.ROOT));
        }
        List<RenterProfile> profiles = new ArrayList<>();
        for (String value : Optional.ofNullable(request.renterProfiles()).orElse(List.of())) {
            RenterProfile profile = RenterProfile.parse(value).orElseThrow(() -> new IllegalArgumentException("Unsupported renter profile"));
            if (!profiles.contains(profile)) profiles.add(profile);
        }
        List<PoiCategory> priorities = new ArrayList<>();
        for (String value : Optional.ofNullable(request.priorityCategories()).orElse(List.of())) {
            PoiCategory category = PoiCategory.parse(value).orElseThrow(() -> new IllegalArgumentException("Unsupported amenity category"));
            if (!priorities.contains(category)) priorities.add(category);
        }
        int radius = defaultRadius;
        if (request.radiusMeters() != null) {
            radius = request.radiusMeters();
            // Reject rather than silently clamp: the customer must know the requested scan was not performed.
            if (radius <= 0) throw new IllegalArgumentException("radiusMeters must be positive");
            if (radius > maxRadius) throw new IllegalArgumentException("radiusMeters must not exceed " + maxRadius);
        }
        String target = request.targetPlaceQuery() == null ? null : request.targetPlaceQuery().strip();
        if (target != null && (target.isEmpty() || target.length() > 120 || target.chars().anyMatch(Character::isISOControl)))
            target = target != null && target.isEmpty() ? null : rejectTarget();
        return new Resolved(List.copyOf(ids), List.copyOf(profiles), List.copyOf(priorities), radius, target,
                request.originalPositions() == null ? Map.of() : request.originalPositions());
    }

    private static String rejectTarget() { throw new IllegalArgumentException("targetPlaceQuery is invalid"); }

    // ---------------------------------------------------------------- scan state

    private static final class Scan {
        final String id;
        final Integer position;
        final Row row;
        String status = "OK", detail;
        final List<Poi> raw = new ArrayList<>();
        final Map<PoiCategory, String> coverage = new EnumMap<>(PoiCategory.class);
        boolean complete, needsMore;
        int pages, nextOffset, hits, misses;
        Instant fetchedAt;
        final List<String> warnings = new ArrayList<>();
        boolean evaluated;

        Scan(String id, Integer position, Row row) { this.id = id; this.position = position; this.row = row; }
        boolean scannable() { return evaluated && status.equals("OK"); }
    }

    // ---------------------------------------------------------------- orchestration

    public Response compare(Request request) {
        Resolved in = validate(request);
        Map<String, Row> rows = load(in.ids());
        LinkedHashMap<PoiCategory, Integer> groups = groups(in);
        List<String> providerCategories = groups.keySet().stream().flatMap(c -> c.providerCategories().stream()).distinct().toList();

        List<Scan> scans = new ArrayList<>();
        int index = 0;
        for (String id : in.ids()) {
            index++;
            Row row = rows.get(id);
            Scan scan = new Scan(id, in.positions().getOrDefault(id, index), row);
            if (row == null || !row.available()) { scan.status = "NOT_AVAILABLE"; scan.detail = "Tin không còn hiển thị công khai"; }
            else if (row.latitude() == null || row.longitude() == null) { scan.status = "MISSING_COORDINATES"; scan.detail = "Tin chưa có tọa độ"; }
            else if (!validCoordinates(row.latitude(), row.longitude())) { scan.status = "INVALID_COORDINATES"; scan.detail = "Tọa độ tin không hợp lệ"; }
            else scan.evaluated = true;
            scans.add(scan);
        }
        boolean configured = fetcher.configured();
        PoiFetcher.Turn turn = new PoiFetcher.Turn(maxRequestsPerTurn, Duration.ofSeconds(deadlineSeconds));
        String providerFailure = null;
        boolean budget = false;
        if (configured) {
            try { budget = scanAll(turn, scans, in, groups, providerCategories); }
            catch (GeoapifyException ex) { providerFailure = ex.code().name(); }
        }
        return build(in, scans, groups, turn, configured, providerFailure, budget);
    }

    /** Returns true when the budget cut the scan short. Provider-wide credential failure is rethrown. */
    private boolean scanAll(PoiFetcher.Turn turn, List<Scan> scans, Resolved in, LinkedHashMap<PoiCategory, Integer> groups,
                            List<String> providerCategories) {
        boolean budget = false;
        // Stage 1: one multi-category page per candidate, so every candidate gets the same first coverage.
        for (Scan scan : scans) {
            if (!scan.scannable()) continue;
            try {
                PoiFetcher.Page page = fetch(turn, scan, in.radius(), providerCategories, 0);
                absorb(scan, page, 0);
            } catch (GeoapifyException ex) { budget |= failed(scan, ex); }
        }
        // Stage 2: continue paging only for candidates whose first page was full.
        for (int pageNo = 1; pageNo < maxPages; pageNo++) {
            for (Scan scan : scans) {
                if (!scan.scannable() || scan.complete || !scan.needsMore || scan.pages != pageNo) continue;
                try {
                    PoiFetcher.Page page = fetch(turn, scan, in.radius(), providerCategories, scan.nextOffset);
                    absorb(scan, page, scan.nextOffset);
                } catch (GeoapifyException ex) { budget |= failed(scan, ex); }
            }
        }
        // Stage 3: groups with no hit in a truncated batch get their own query (priority groups first, round-robin).
        for (PoiCategory group : groups.keySet()) {
            for (Scan scan : scans) {
                if (!scan.scannable() || scan.complete) continue;
                if (hasHit(scan, group, in.radius())) continue;
                try {
                    PoiFetcher.Page page = fetch(turn, scan, in.radius(), group.providerCategories(), 0);
                    scan.raw.addAll(page.pois());
                    scan.coverage.put(group, page.featureCount() < fetcher.pageSize() ? "CHECKED" : "PARTIAL");
                } catch (GeoapifyException ex) { budget |= failed(scan, ex); }
            }
        }
        return budget;
    }

    private PoiFetcher.Page fetch(PoiFetcher.Turn turn, Scan scan, int radius, List<String> categories, int offset) {
        PoiFetcher.Page page = fetcherPage(turn, scan, radius, categories, offset);
        scan.pages++;
        if (page.fromCache()) scan.hits++; else scan.misses++;
        if (scan.fetchedAt == null || page.fetchedAt().isBefore(scan.fetchedAt)) scan.fetchedAt = page.fetchedAt();
        if (page.skipped() > 0) scan.warnings.add(page.skipped() + " địa điểm bị bỏ qua vì tọa độ không hợp lệ");
        return page;
    }

    private PoiFetcher.Page fetcherPage(PoiFetcher.Turn turn, Scan scan, int radius, List<String> categories, int offset) {
        return fetcher.page(turn, scan.row.latitude(), scan.row.longitude(), radius, categories, offset);
    }

    private void absorb(Scan scan, PoiFetcher.Page page, int offset) {
        scan.raw.addAll(page.pois());
        if (page.featureCount() < fetcher.pageSize()) { scan.complete = true; scan.needsMore = false; }
        else { scan.needsMore = true; scan.nextOffset = offset + fetcher.pageSize(); } // offset follows the real page size
    }

    /** True means a budget/limit condition; provider auth/config failures abort every candidate. */
    private boolean failed(Scan scan, GeoapifyException ex) {
        switch (ex.code()) {
            case BUDGET_EXHAUSTED, UPSTREAM_RATE_LIMITED -> {
                if (scan.status.equals("OK")) scan.warnings.add("Chưa quét hết tiện ích do giới hạn lượt truy vấn");
                return true;
            }
            case UPSTREAM_AUTH, GEOAPIFY_NOT_CONFIGURED -> throw ex;
            default -> {
                scan.status = "PROVIDER_ERROR";
                scan.detail = "Nguồn tiện ích tạm thời không phản hồi hợp lệ";
                return false;
            }
        }
    }

    private boolean hasHit(Scan scan, PoiCategory group, int radius) {
        return scan.raw.stream().anyMatch(poi -> group.matches(poi.categories()) && poi.straightLineDistanceMeters() <= radius);
    }

    // ---------------------------------------------------------------- listing data

    Map<String, Row> load(List<String> ids) {
        String sql = "SELECT l.id, (l.active = true AND l.status = 'PUBLISHED' AND (l.expires_at IS NULL OR l.expires_at > now())) AS available, "
                + "a.latitude, a.longitude FROM listings l LEFT JOIN listing_addresses a ON a.listing_id = l.id AND a.active = true "
                + "WHERE l.id IN (:ids)";
        List<Row> result = jdbc.query(sql, new MapSqlParameterSource("ids", ids), (rs, n) -> new Row(
                rs.getString("id"), rs.getBoolean("available"),
                rs.getBigDecimal("latitude") == null ? null : rs.getBigDecimal("latitude").doubleValue(),
                rs.getBigDecimal("longitude") == null ? null : rs.getBigDecimal("longitude").doubleValue()));
        Map<String, Row> byId = new HashMap<>();
        for (Row row : result) byId.putIfAbsent(row.id().toLowerCase(Locale.ROOT), row);
        return byId;
    }

    private static boolean validCoordinates(double lat, double lon) {
        return Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0);
    }

    // ---------------------------------------------------------------- categories

    /** Explicit customer priorities rank above preset groups; both stay in a deterministic order. */
    private LinkedHashMap<PoiCategory, Integer> groups(Resolved in) {
        LinkedHashMap<PoiCategory, Integer> groups = new LinkedHashMap<>();
        for (PoiCategory category : in.priorities()) groups.put(category, WEIGHT_PRIORITY);
        for (RenterProfile profile : in.profiles()) for (PoiCategory category : profile.presets()) groups.putIfAbsent(category, WEIGHT_PRESET);
        if (in.target() != null && targetEligible(in)) for (PoiCategory category : EDUCATION) groups.putIfAbsent(category, WEIGHT_PRESET);
        if (groups.isEmpty()) for (PoiCategory category : DEFAULT_GROUPS) groups.put(category, WEIGHT_PRESET);
        if (groups.size() > MAX_CATEGORY_GROUPS) {
            LinkedHashMap<PoiCategory, Integer> trimmed = new LinkedHashMap<>();
            groups.entrySet().stream().limit(MAX_CATEGORY_GROUPS).forEach(e -> trimmed.put(e.getKey(), e.getValue()));
            return trimmed;
        }
        return groups;
    }

    private static boolean targetEligible(Resolved in) {
        return in.profiles().contains(RenterProfile.STUDENT) || in.profiles().contains(RenterProfile.FAMILY_WITH_CHILDREN)
                || in.priorities().stream().anyMatch(EDUCATION::contains);
    }

    // ---------------------------------------------------------------- response

    private Response build(Resolved in, List<Scan> scans, LinkedHashMap<PoiCategory, Integer> groups, PoiFetcher.Turn turn,
                           boolean configured, String providerFailure, boolean budget) {
        List<String> warnings = new ArrayList<>();
        List<CandidateEvidence> candidates = new ArrayList<>();
        Map<String, List<Poi>> deduped = new HashMap<>();
        for (Scan scan : scans) {
            List<Poi> pois = scan.evaluated ? PoiParser.withinRadius(PoiParser.dedupe(scan.raw), in.radius()) : List.of();
            deduped.put(scan.id, pois);
            if (scan.scannable() && configured && providerFailure == null) finishCoverage(scan, groups, pois);
        }
        Target target = target(in, scans, deduped, configured && providerFailure == null);

        // Fair comparison: only categories that were actually known for every evaluated candidate count.
        List<Scan> evaluated = scans.stream().filter(s -> s.scannable() && configured && providerFailure == null).toList();
        Set<PoiCategory> comparable = new LinkedHashSet<>();
        for (PoiCategory group : groups.keySet()) {
            boolean all = !evaluated.isEmpty();
            for (Scan scan : evaluated) all &= known(scan, group, deduped.get(scan.id));
            if (all) comparable.add(group);
        }
        boolean targetUsed = target.status().equals("CONFIRMED");
        List<RankedListing> ranked = new ArrayList<>();
        if (!evaluated.isEmpty() && (!comparable.isEmpty() || targetUsed)) {
            for (Scan scan : evaluated) ranked.add(score(scan, deduped.get(scan.id), groups, comparable, in.radius(), targetUsed ? target : null));
            ranked.sort(Comparator.comparingDouble(RankedListing::score).reversed()
                    .thenComparing(r -> r.reasons().size(), Comparator.reverseOrder())
                    .thenComparing(r -> r.originalPosition() == null ? Integer.MAX_VALUE : r.originalPosition()));
        }
        List<RankedListing> numbered = new ArrayList<>();
        for (RankedListing r : ranked) numbered.add(new RankedListing(r.listingId(), numbered.size() + 1, r.originalPosition(), r.score(), r.reasons(), r.missing()));
        List<String> recommended = numbered.stream().filter(r -> r.score() > 0).limit(recommendationLimit)
                .map(RankedListing::listingId).toList();
        if (evaluated.size() > 1 && comparable.isEmpty() && !targetUsed)
            warnings.add("Độ phủ dữ liệu tiện ích của các phòng chưa đồng đều nên chưa thể xếp hạng công bằng.");
        if (!configured) warnings.add("Chưa cấu hình nguồn dữ liệu tiện ích (Geoapify) nên chưa đánh giá được tiện ích xung quanh.");
        if (providerFailure != null) warnings.add("Nguồn dữ liệu tiện ích đang lỗi (" + providerFailure + "); chưa đánh giá được tiện ích.");
        if (budget) warnings.add("Đã chạm giới hạn truy vấn tiện ích; một số nhóm chưa được quét (PARTIAL).");

        boolean usable = configured && providerFailure == null;
        for (Scan scan : scans) {
            List<Poi> pois = deduped.get(scan.id);
            candidates.add(evidence(scan, groups, pois, in.radius(), usable, scan.id));
        }
        int compared = evaluated.size();
        String status;
        if (!configured) status = "GEOAPIFY_NOT_CONFIGURED";
        else if (providerFailure != null) status = "PROVIDER_UNAVAILABLE";
        else if (compared == 0) status = scans.stream().anyMatch(s -> s.status.equals("PROVIDER_ERROR")) ? "PROVIDER_UNAVAILABLE"
                : budget ? "BUDGET_EXHAUSTED" : "NO_VALID_CANDIDATES";
        else if (budget || evaluated.stream().anyMatch(s -> !s.complete) || scans.stream().anyMatch(s -> !s.status.equals("OK")) || comparable.size() < groups.size()) status = "PARTIAL";
        else status = "OK";
        List<Source> sources = usable ? List.of(new Source("Geoapify Places", "© Geoapify, © OpenStreetMap contributors", "Open Database License",
                "https://www.openstreetmap.org/copyright")) : List.of();
        return new Response(status, scans.size(), compared, recommendationLimit, in.radius(),
                in.profiles().stream().map(Enum::name).toList(), groups.keySet().stream().map(Enum::name).toList(), target, candidates,
                recommended, List.copyOf(numbered), warnings, sources,
                new Usage(turn.providerRequests(), turn.cacheHits(), turn.pages()), Instant.now().toString());
    }

    private void finishCoverage(Scan scan, LinkedHashMap<PoiCategory, Integer> groups, List<Poi> pois) {
        for (PoiCategory group : groups.keySet()) {
            if (scan.complete) { scan.coverage.put(group, "CHECKED"); continue; }
            scan.coverage.putIfAbsent(group, pois.stream().anyMatch(p -> group.matches(p.categories())) ? "PARTIAL" : "UNCHECKED");
        }
        if (!scan.complete) scan.warnings.add("Kết quả tiện ích có thể chưa đầy đủ (đã quét tối đa " + maxPages + " trang).");
    }

    private static boolean known(Scan scan, PoiCategory group, List<Poi> pois) {
        String coverage = scan.coverage.getOrDefault(group, "UNCHECKED");
        return coverage.equals("CHECKED") || (coverage.equals("PARTIAL") && pois.stream().anyMatch(p -> group.matches(p.categories())));
    }

    private CandidateEvidence evidence(Scan scan, LinkedHashMap<PoiCategory, Integer> groups, List<Poi> pois, int radius, boolean usable, String id) {
        List<CategoryEvidence> categories = new ArrayList<>();
        String status = scan.status;
        String detail = scan.detail;
        if (scan.evaluated && !usable) { status = "NOT_CHECKED"; detail = "Chưa có dữ liệu tiện ích"; }
        else if (scan.scannable()) {
            for (PoiCategory group : groups.keySet()) {
                List<Poi> matches = pois.stream().filter(p -> group.matches(p.categories()))
                        .sorted(Comparator.comparingDouble(Poi::straightLineDistanceMeters)).toList();
                String coverage = scan.coverage.getOrDefault(group, "UNCHECKED");
                categories.add(new CategoryEvidence(group.name(), group.label(), coverage, matches.size(), coverage.equals("PARTIAL"),
                        matches.stream().limit(3).map(p -> sample(p, group)).toList()));
            }
            if (!scan.complete || scan.coverage.containsValue("UNCHECKED") || scan.coverage.containsValue("PARTIAL")) status = "PARTIAL";
            if (pois.isEmpty() && scan.complete) status = "EMPTY_PROVIDER_RESULT";
        }
        String cache = scan.hits == 0 && scan.misses == 0 ? "NONE" : scan.misses == 0 ? "HIT" : scan.hits == 0 ? "MISS" : "MIXED";
        return new CandidateEvidence(id, scan.position, status, detail, categories, scan.complete, scan.pages,
                scan.fetchedAt == null ? null : scan.fetchedAt.toString(), cache, List.copyOf(new LinkedHashSet<>(scan.warnings)));
    }

    private static PoiSample sample(Poi poi, PoiCategory group) {
        return new PoiSample(poi.named() ? poi.name() : group.label() + " chưa có tên", poi.formatted(),
                (int) Math.round(poi.straightLineDistanceMeters()), poi.providerDistanceMeters(), poi.coordinateSource());
    }

    // ---------------------------------------------------------------- ranking

    private RankedListing score(Scan scan, List<Poi> pois, LinkedHashMap<PoiCategory, Integer> groups, Set<PoiCategory> comparable,
                                int radius, Target target) {
        double total = 0, weights = 0;
        List<Object[]> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (PoiCategory group : groups.keySet()) {
            Optional<Poi> nearest = pois.stream().filter(p -> group.matches(p.categories()))
                    .min(Comparator.comparingDouble(Poi::straightLineDistanceMeters));
            if (!comparable.contains(group)) { missing.add(group.label() + ": chưa đủ dữ liệu để so sánh"); continue; }
            int weight = groups.get(group);
            double s = nearest.map(p -> Math.max(0, 1 - p.straightLineDistanceMeters() / radius)).orElse(0.0);
            total += weight * s;
            weights += weight;
            if (nearest.isPresent()) found.add(new Object[] {weight * s, group, nearest.get()});
            else missing.add("Không thấy " + group.label().toLowerCase(Locale.ROOT) + " trong dữ liệu trong bán kính " + radius + " m");
        }
        if (target != null) {
            Optional<TargetOption> option = target.options().stream().filter(o -> o.listingId().equals(scan.id)).findFirst();
            if (option.isPresent()) {
                total += WEIGHT_TARGET * Math.max(0, 1 - option.get().distanceMeters() / (double) radius);
                weights += WEIGHT_TARGET;
            }
        }
        double score = weights == 0 ? 0 : Math.round(total / weights * 1000.0) / 10.0;
        found.sort((a, b) -> Double.compare((double) b[0], (double) a[0]));
        List<Reason> reasons = found.stream().limit(3).map(o -> {
            PoiCategory group = (PoiCategory) o[1];
            Poi poi = (Poi) o[2];
            return new Reason(group.name(), group.label(), poi.named() ? poi.name() : group.label() + " chưa có tên",
                    (int) Math.round(poi.straightLineDistanceMeters()));
        }).toList();
        return new RankedListing(scan.id, 0, scan.position, score, reasons, missing);
    }

    // ---------------------------------------------------------------- target place

    static String fold(String value) {
        return Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private Target target(Resolved in, List<Scan> scans, Map<String, List<Poi>> pois, boolean usable) {
        if (in.target() == null) return new Target(null, "NOT_REQUESTED", List.of());
        if (!usable || !targetEligible(in)) return new Target(in.target(), "NOT_EVALUATED", List.of());
        List<String> tokens = Arrays.stream(fold(in.target()).split(" ")).filter(t -> !t.isBlank() && !GENERIC_TARGET_WORDS.contains(t)).toList();
        if (tokens.isEmpty()) return new Target(in.target(), "NOT_EVALUATED", List.of());
        List<TargetOption> exact = new ArrayList<>(), near = new ArrayList<>();
        Set<String> exactIdentities = new LinkedHashSet<>();
        for (Scan scan : scans) {
            if (!scan.scannable()) continue;
            List<Poi> bestMatches = new ArrayList<>();
            for (Poi poi : pois.get(scan.id)) {
                // An unnamed place can never be evidence for a specific school or campus.
                if (!poi.named() || EDUCATION.stream().noneMatch(c -> c.matches(poi.categories()))) continue;
                String haystack = fold(poi.name() + " " + (poi.formatted() == null ? "" : poi.formatted()));
                long matched = tokens.stream().filter(t -> Arrays.asList(haystack.split(" ")).contains(t)).count();
                TargetOption option = new TargetOption(scan.id, poi.name(), poi.formatted(), (int) Math.round(poi.straightLineDistanceMeters()));
                if (matched == tokens.size()) { bestMatches.add(poi); exact.add(option); }
                else if (matched * 2 >= tokens.size()) near.add(option);
            }
            // The same named+addressed place seen as several OSM nodes is one campus; different addresses are not merged.
            for (Poi poi : bestMatches) exactIdentities.add(fold(poi.name()) + "|" + fold(poi.formatted() == null ? "" : poi.formatted()));
        }
        if (!exact.isEmpty() && exactIdentities.size() == 1) {
            Map<String, TargetOption> nearestPerListing = new LinkedHashMap<>();
            for (TargetOption option : exact) nearestPerListing.merge(option.listingId(), option, (a, b) -> a.distanceMeters() <= b.distanceMeters() ? a : b);
            return new Target(in.target(), "CONFIRMED", List.copyOf(nearestPerListing.values()));
        }
        List<TargetOption> options = (exact.isEmpty() ? near : exact).stream()
                .sorted(Comparator.comparingInt(TargetOption::distanceMeters)).limit(5).toList();
        // Never auto-pick the first POI: ambiguous or partially-matching places require confirmation.
        return new Target(in.target(), options.isEmpty() ? "NOT_FOUND" : "NEEDS_CONFIRMATION", options);
    }
}
