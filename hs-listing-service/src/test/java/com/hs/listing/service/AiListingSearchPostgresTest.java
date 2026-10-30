package com.hs.listing.service;

import com.hs.listing.config.ListingPolicyInitializer;
import com.hs.listing.config.ListingReferenceDataInitializer;
import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.dto.request.AiListingSearchRequest.Condition;
import com.hs.listing.dto.request.AiListingSearchRequest.NearbyCriteria;
import com.hs.listing.dto.request.AiListingSearchRequest.SearchContext;
import com.hs.listing.dto.response.AiListingSearchResponse;
import com.hs.listing.nearby.NearbyQueryService;
import com.hs.listing.nearby.NearbyRepository;
import com.hs.listing.nearby.NearbySettings;
import com.hs.listing.nearby.NearbyTypes;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.FurnishingItemRepository;
import com.hs.listing.repository.ListingPolicyDefinitionRepository;
import com.hs.listing.repository.ListingRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI search v2 against the real Hibernate schema and seed data: every hard predicate is compared with an
 * independent SQL/Java ground truth. Destructive schema creation is opt-in and restricted to a disposable
 * database named hs_ai_search_test; no provider is reachable and reads must not create scan work.
 */
@EnabledIfEnvironmentVariable(named = "HS_AI_SEARCH_TEST_PG_URL", matches = "jdbc:postgresql://[^/]+/hs_ai_search_test")
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = AiListingSearchPostgresTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${HS_AI_SEARCH_TEST_PG_URL}",
        "spring.datasource.username=postgres", "spring.datasource.password=ai-search-test-only",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiListingSearchPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses = ListingRepository.class)
    static class Config {}

    private static final String PUBLIC = "l.active AND l.status = 'PUBLISHED' AND (l.expires_at IS NULL OR l.expires_at > now())";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AmenityRepository amenities;
    @Autowired FurnishingItemRepository furnishings;
    @Autowired ListingPolicyDefinitionRepository policies;

    private AiListingSearchService service;

    private void execute(String sql) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute(sql);
        }
    }

    private Set<String> truth(String predicate, Object... args) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT l.id FROM listings l JOIN listing_addresses a ON a.listing_id = l.id AND a.active"
                + " WHERE " + PUBLIC + " AND (" + predicate + ")", String.class, args));
    }

    private static AiListingSearchRequest v2(List<Condition> all, String sort, int limit, SearchContext context,
                                             List<NearbyCriteria> nearby, List<String> scope, List<String> exclude) {
        return new AiListingSearchRequest(List.of(), sort, limit, null, null, 2, Condition.allOf(all), nearby, context, scope, exclude, true);
    }

    private AiListingSearchResponse search(List<Condition> all) {
        return service.search(v2(all, "newest", 20, null, null, null, null));
    }

    private void assertMatches(Set<String> expected, AiListingSearchResponse response, String label) {
        assertFalse(expected.isEmpty(), label + ": the fixture must exercise the predicate");
        assertEquals(expected.size(), response.total(), label + ": total counted before LIMIT");
        assertEquals(Math.min(expected.size(), 20), response.listingIds().size(), label);
        assertTrue(expected.containsAll(response.listingIds()), label + ": every returned listing satisfies the predicate");
    }

    private Map<String, Long> scanState() {
        Map<String, Long> state = new LinkedHashMap<>();
        for (String table : List.of("nearby_scan_jobs", "nearby_scan_runs", "nearby_places", "listing_nearby_places"))
            state.put(table, jdbc.queryForObject("select count(*) from " + table, Long.class));
        state.put("leased", jdbc.queryForObject("select count(*) from nearby_scan_jobs where lease_token is not null", Long.class));
        state.put("pending", jdbc.queryForObject("select count(*) from nearby_scan_jobs where status not in ('PARTIAL','COMPLETE','FAILED')", Long.class));
        return state;
    }

    @Test void everyHardPredicateIsAppliedInPostgresBeforeLimit() throws Exception {
        Path sqlPath = Path.of(System.getProperty("hs.seed.sql")).toAbsolutePath();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            new ListingReferenceDataInitializer(amenities, furnishings).run();
            new ListingPolicyInitializer(policies).run();
        });
        execute("INSERT INTO roles(id,active,name) VALUES ('ai-test-role',true,'ADMIN'); "
                + "INSERT INTO users(id,active,email,on_boarded,username,role_id) VALUES ('ai-test-owner',true,'ai@example.invalid',true,'homespace','ai-test-role');");
        execute(Files.readString(sqlPath));
        var settings = offlineNearbySettings();
        var named = new NamedParameterJdbcTemplate(dataSource);
        service = new AiListingSearchService(named);
        service.setNearbyQuery(new NearbyQueryService(new NearbyRepository(named, transactions, settings), settings));
        Map<String, Long> before = scanState();

        // Ward + category + price: total is counted over all matches, LIMIT only trims the page.
        var page = service.search(v2(List.of(Condition.leaf("ward_id", "eq", "24588"), Condition.leaf("category", "eq", "ROOM"),
                Condition.leaf("price_amount", "lte", "4000000")), "price_asc", 5, null, null, null, null));
        Set<String> wardRooms = truth("a.ward_id = '24588' AND l.category = 'ROOM' AND l.price_amount <= 4000000");
        assertEquals(wardRooms.size(), page.total());
        assertEquals(5, page.listingIds().size());
        assertTrue(wardRooms.containsAll(page.listingIds()));
        assertTrue(page.evidence().stream().allMatch(e -> e.checks().stream().allMatch(c -> c.status().equals("MATCH"))),
                "Returned listings carry MATCH evidence for each condition");

        assertMatches(truth("EXISTS (SELECT 1 FROM listing_amenities la JOIN amenities am ON am.id = la.amenity_id WHERE la.listing_id = l.id AND am.code = 'ELEVATOR')"),
                search(List.of(Condition.leaf("amenity.ELEVATOR", "eq", "true"))), "amenity.ELEVATOR");
        assertMatches(truth("l.category = 'ROOM' AND EXISTS (SELECT 1 FROM listing_room_details d WHERE d.listing_id = l.id AND d.restroom_type = 'PRIVATE')"),
                search(List.of(Condition.leaf("room.restroom_type", "eq", "PRIVATE"))), "room.restroom_type");
        assertMatches(truth("EXISTS (SELECT 1 FROM listing_policy_values t WHERE t.listing_id = l.id AND t.policy_code = 'depositRefundDays'"
                        + " AND t.policy_value ~ '^-?[0-9]{1,9}$' AND CAST(t.policy_value AS integer) <= 7)"),
                search(List.of(Condition.leaf("policy.depositRefundDays.value", "lte", "7"))), "integer policy");
        assertMatches(truth("(SELECT coalesce(sum(t.quantity), 0) FROM listing_furnishing_assets t WHERE t.listing_id = l.id AND t.item_code = 'BED') >= 1"),
                search(List.of(Condition.leaf("furnishing.BED.total_quantity", "gte", "1"))), "furnishing total quantity");
        assertMatches(truth("(l.category = 'ROOM' AND l.price_amount <= 3000000) OR (l.category = 'HOUSE' AND l.price_amount <= 15000000)"),
                search(List.of(Condition.anyOf(List.of(
                        Condition.allOf(List.of(Condition.leaf("category", "eq", "ROOM"), Condition.leaf("price_amount", "lte", "3000000"))),
                        Condition.allOf(List.of(Condition.leaf("category", "eq", "HOUSE"), Condition.leaf("price_amount", "lte", "15000000"))))))),
                "OR groups");

        // Per-person rent is UNKNOWN without an occupant count; with it, it is the group's monthly rent.
        String rent = "CASE WHEN l.price_unit IN ('MONTH','ROOM_MONTH') THEN l.price_amount WHEN l.price_unit = 'PERSON_MONTH' THEN l.price_amount * ? END";
        var withoutContext = search(List.of(Condition.leaf("rent.monthly_amount", "lte", "4000000")));
        assertEquals(truth("l.price_unit IN ('MONTH','ROOM_MONTH') AND l.price_amount <= 4000000").size(), withoutContext.total());
        long perPerson = truth("l.price_unit = 'PERSON_MONTH'").size();
        assertEquals(perPerson, withoutContext.unknownTotal());
        if (perPerson > 0) assertEquals(List.of("occupants"), withoutContext.contextMissing());
        assertMatches(truth("(" + rent + ") <= 4000000", 2), service.search(v2(List.of(Condition.leaf("rent.monthly_amount", "lte", "4000000")),
                "rent_asc", 20, new SearchContext(2, null, null, null, null), null, null, null)), "rent with occupants");

        // Scope and exclusion are inside the same public predicate.
        List<String> scope = new ArrayList<>(wardRooms).subList(0, 10);
        var scoped = service.search(v2(List.of(Condition.leaf("category", "eq", "ROOM")), "newest", 20, null, null, scope, scope.subList(0, 2)));
        assertEquals(8, scoped.total());
        assertTrue(scope.subList(2, 10).containsAll(scoped.listingIds()));

        // Named target: straight-line distance from stored coordinates, no link or scan needed.
        String placeId = jdbc.queryForObject("SELECT p.id FROM nearby_places p JOIN listing_nearby_places lp ON lp.place_id = p.id"
                + " WHERE p.active GROUP BY p.id ORDER BY count(*) DESC, p.id LIMIT 1", String.class);
        var place = jdbc.queryForMap("SELECT latitude, longitude FROM nearby_places WHERE id = ?", placeId);
        double lat = ((Number) place.get("latitude")).doubleValue(), lon = ((Number) place.get("longitude")).doubleValue();
        Map<String, Double> near = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT l.id, a.latitude, a.longitude FROM listings l JOIN listing_addresses a ON a.listing_id = l.id AND a.active"
                + " WHERE " + PUBLIC + " AND l.category = 'ROOM' AND a.latitude IS NOT NULL")) {
            double d = NearbyTypes.distance(lat, lon, ((Number) row.get("latitude")).doubleValue(), ((Number) row.get("longitude")).doubleValue());
            if (d <= 1000) near.put((String) row.get("id"), d);
        }
        var targeted = service.search(v2(List.of(Condition.leaf("category", "eq", "ROOM")), "target_distance", 20, null,
                List.of(new NearbyCriteria(placeId, null, 1000)), null, null));
        assertMatches(near.keySet(), targeted, "named target");
        double previous = -1;
        for (var evidence : targeted.evidence()) {
            assertNotNull(evidence.targetDistanceMeters());
            assertEquals(near.get(evidence.listingId()), evidence.targetDistanceMeters(), 0.5);
            assertTrue(evidence.targetDistanceMeters() >= previous, "Sorted by distance to the chosen target");
            previous = evidence.targetDistanceMeters();
        }

        // Beyond the 2 km scan radius a named target still matches by coordinates, without any listing-place link.
        Set<String> linked = new HashSet<>(jdbc.queryForList("SELECT listing_id FROM listing_nearby_places WHERE place_id = ?", String.class, placeId));
        Map<String, Double> far = new LinkedHashMap<>();
        for (var row : jdbc.queryForList("SELECT l.id, a.latitude, a.longitude FROM listings l JOIN listing_addresses a ON a.listing_id = l.id AND a.active"
                + " WHERE " + PUBLIC + " AND a.latitude IS NOT NULL")) {
            double d = NearbyTypes.distance(lat, lon, ((Number) row.get("latitude")).doubleValue(), ((Number) row.get("longitude")).doubleValue());
            if (d <= 5000) far.put((String) row.get("id"), d);
        }
        long unlinkedBeyondScan = far.entrySet().stream().filter(e -> e.getValue() > 2000 && !linked.contains(e.getKey())).count();
        assertTrue(unlinkedBeyondScan > 0, "fixture has listings 2-5 km from the target with no link to it");
        var fiveKm = service.search(v2(List.of(), "target_distance", 20, null, List.of(new NearbyCriteria(placeId, null, 5000)), null, null));
        assertEquals(far.size(), fiveKm.total(), "5 km target distance counts every listing by coordinates");
        assertEquals(0L, fiveKm.unknownTotal() == null ? 0L : fiveKm.unknownTotal());

        // A specific POI kind needs that source category; partial coverage keeps non-matches UNKNOWN, not "none nearby".
        // The seed's 100 real POIs contain convenience stores but no supermarket (category 7332005).
        Set<String> convenience = kindWithin("9361009", 2000);
        assertMatches(convenience, service.search(v2(List.of(), "newest", 20, null,
                List.of(new NearbyCriteria(null, null, 2000, "CONVENIENCE_STORE")), null, null)), "convenience store within 2 km");
        assertTrue(kindWithin("7332005", 2000).isEmpty());
        var supermarket = service.search(v2(List.of(), "newest", 20, null, List.of(new NearbyCriteria(null, null, 500, "SUPERMARKET")), null, null));
        assertEquals(0, supermarket.total(), "A convenience store in the same SHOPPING group never satisfies a supermarket request");
        Set<String> provenAbsent = truth("EXISTS (SELECT 1 FROM nearby_scan_runs r JOIN nearby_scan_jobs j ON j.id = r.job_id WHERE j.listing_id = l.id"
                + " AND r.coverage_status = 'COMPLETE_PROVIDER_RESPONSE' AND r.radius_m >= 500 AND jsonb_exists(r.requested_groups, 'SHOPPING'))");
        assertEquals(truth("true").size() - provenAbsent.size(), supermarket.unknownTotal(),
                "Without complete coverage a listing with no supermarket evidence is unknown, not proven absent");
        Set<String> pharmacyOnly = kindWithin("7326", 1000);
        Set<String> hospital = kindWithin("7321", 1000);
        pharmacyOnly.removeAll(hospital);
        assertFalse(pharmacyOnly.isEmpty(), "fixture has listings near a pharmacy but no hospital");
        var hospitalNear = List.of(new NearbyCriteria(null, null, 1000, "HOSPITAL_POLYCLINIC"));
        assertEquals(hospital.size(), service.search(v2(List.of(), "newest", 20, null, hospitalNear, null, null)).total());
        List<String> pharmacyScope = new ArrayList<>(pharmacyOnly).subList(0, Math.min(20, pharmacyOnly.size()));
        assertEquals(0, service.search(v2(List.of(), "newest", 20, null, hospitalNear, pharmacyScope, null)).total(),
                "A pharmacy in the same HEALTHCARE group never satisfies a hospital request");

        var invalid = assertThrows(AiSearchValidationException.class, () -> search(List.of(Condition.leaf("room.restroom_type", "eq", "PRIVATE_ONLY"))));
        assertEquals("INVALID_VALUE", invalid.reason());
        verifyValueAdvice(named);
        assertEquals(before, scanState(), "Search and evidence reads never enqueue, lease or write scan data");
        System.out.println("AI SEARCH PG PASS: ward/category/price, amenity, room enum, integer policy, furnishing total, OR, per-person rent"
                + " (unknown=" + perPerson + "), scope/exclude, target distance (" + near.size() + " within 1 km; " + far.size() + " within 5 km, "
                + unlinkedBeyondScan + " unlinked beyond 2 km), convenience 2 km ("
                + convenience.size() + "), supermarket 0 (unknown=" + supermarket.unknownTotal() + "), hospital vs pharmacy-only (" + hospital.size() + "/" + pharmacyOnly.size()
                + "); scan tables unchanged");
    }

    private void verifyValueAdvice(NamedParameterJdbcTemplate named) {
        // Controlled retrieval positives/negatives in this disposable PostgreSQL only.
        var ids = jdbc.queryForList("SELECT id FROM listings WHERE category='APARTMENT' ORDER BY id LIMIT 16", String.class);
        assertEquals(16, ids.size());
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE listings SET title='VALUE_TEST',price_amount=?,price_unit='MONTH',currency='VND',area_m2=?,"
                    + "available_from=current_date,status='PUBLISHED',expires_at=now()+interval '1 day' WHERE id=?",
                    i < 4 ? 3000000 + i * 400000 : 4800000, 20 + i, ids.get(i));
            jdbc.update("UPDATE listing_addresses SET city_id='12',ward_id='24332' WHERE listing_id=?", ids.get(i));
            jdbc.update("UPDATE listing_apartment_details SET bedroom_count=1,bathroom_count=1,max_occupants=3,"
                    + "furnishing_status='FULLY_FURNISHED',floor_number=5 WHERE listing_id=?", ids.get(i));
        }
        jdbc.update("UPDATE listings SET price_amount=4600000 WHERE id=?", ids.get(4));
        jdbc.update("UPDATE listings SET price_amount=5000000 WHERE id=?", ids.get(5));
        jdbc.update("UPDATE listings SET price_amount=5000001 WHERE id=?", ids.get(6));
        jdbc.update("UPDATE listings SET price_amount=4400000 WHERE id=?", ids.get(7));
        jdbc.update("UPDATE listing_addresses SET ward_id='24588' WHERE listing_id=?", ids.get(8));
        jdbc.update("UPDATE listing_apartment_details SET floor_number=1 WHERE listing_id=?", ids.get(9));
        jdbc.update("UPDATE listings SET status='DRAFT' WHERE id=?", ids.get(10));
        jdbc.update("UPDATE listings SET price_amount=4500000 WHERE id=?", ids.get(11));
        jdbc.update("UPDATE listings SET price_unit='PERSON_MONTH',price_amount=2400000 WHERE id=?", ids.get(12));
        jdbc.update("UPDATE listings SET currency='USD' WHERE id=?", ids.get(13));
        jdbc.update("UPDATE listings SET area_m2=1,available_from=current_date+30 WHERE id=?", ids.get(14));
        jdbc.update("UPDATE listing_apartment_details SET bedroom_count=0,furnishing_status='UNFURNISHED' WHERE listing_id=?", ids.get(14));
        var where = List.of(Condition.leaf("category", "eq", "APARTMENT"), Condition.leaf("city_id", "eq", "12"),
                Condition.leaf("ward_id", "eq", "24332"), Condition.leaf("rent.monthly_amount", "lt", "4500000"),
                Condition.leaf("apartment.floor_number", "gte", "2"));
        var request = new AiListingSearchRequest(List.of(), "newest", 4, "VALUE_TEST", null, 2,
                Condition.allOf(where), null, new SearchContext(2, null, null, null, null), null, null, false);
        var value = new AiListingValueService(named, service, new java.math.BigDecimal("500000"), 8);
        var input = new com.hs.listing.dto.request.AiListingValueRequest(request, ids.subList(0, 4));
        long originalTotal = service.search(request).total();
        var found = value.find(input);
        assertEquals(0, found.budgetCeiling().compareTo(new java.math.BigDecimal("4500000")));
        assertEquals("rent.monthly_amount", found.budgetField());
        assertEquals(Set.of(ids.get(4), ids.get(5), ids.get(11), ids.get(14), ids.get(15)), new HashSet<>(found.candidateIds()),
                "Boundary is 4.5-5m from BUDGET, preserves location/floor/public state/currency/unit; no fixed area or furnishing gate");
        assertTrue(found.candidateIds().size() <= 8);
        assertEquals(originalTotal, service.search(request).total(), "Main result count and original budget remain unchanged");
        assertEquals("4500000", where.get(3).value());
        var inclusive = new ArrayList<>(where);
        inclusive.set(3, Condition.leaf("rent.monthly_amount", "lte", "4500000"));
        var lte = new AiListingSearchRequest(List.of(), "newest", 4, "VALUE_TEST", null, 2,
                Condition.allOf(inclusive), null, request.context(), null, null, false);
        assertFalse(value.find(new com.hs.listing.dto.request.AiListingValueRequest(lte, ids.subList(0, 4))).candidateIds().contains(ids.get(11)),
                "Exactly 4.5m already belongs to an inclusive main budget, never duplicate it as boundary");
        var page = new AiListingSearchRequest(List.of(), "newest", 4, "VALUE_TEST", null, 2,
                Condition.allOf(where), null, request.context(), null, List.of(ids.get(7)), false);
        var nextPage = service.search(page);
        assertEquals(originalTotal, nextPage.matchedTotal(), "Pagination retains the full matched count for the customer's main search");
        assertEquals(originalTotal - 1, nextPage.total(), "The legacy total still counts matches after pagination exclusions");
        var repeated = value.find(new com.hs.listing.dto.request.AiListingValueRequest(page, ids.subList(0, 4), List.of(ids.get(4))));
        assertFalse(repeated.candidateIds().contains(ids.get(4)), "Do not repeat an already shown optional suggestion");
        assertTrue(repeated.candidateIds().contains(ids.get(5)), "Main pagination exclusions must not disable boundary lookup");

        var target = jdbc.queryForMap("SELECT id,latitude,longitude FROM nearby_places WHERE active=true ORDER BY id LIMIT 1");
        for (int i = 0; i < ids.size(); i++) {
            double lat = ((Number)target.get("latitude")).doubleValue() + (i == 15 ? 0.1 : 0.0);
            jdbc.update("UPDATE listing_addresses SET latitude=?,longitude=? WHERE listing_id=?", lat, target.get("longitude"), ids.get(i));
        }
        var nearby = new AiListingSearchRequest(List.of(), "newest", 4, "VALUE_TEST", null, 2,
                Condition.allOf(where), List.of(new NearbyCriteria((String)target.get("id"), null, 1000)), request.context(), null, null, false);
        var around = value.find(new com.hs.listing.dto.request.AiListingValueRequest(nearby, ids.subList(0, 4))).candidateIds();
        assertTrue(around.contains(ids.get(4)));
        assertFalse(around.contains(ids.get(15)), "Named-target distance is preserved before candidate LIMIT");
        jdbc.update("UPDATE listings SET status='DRAFT' WHERE id=?", ids.get(0));
        assertTrue(value.find(input).candidateIds().isEmpty(), "Stale/unpublished displayed page cancels optional lookup");
        jdbc.update("UPDATE listings SET status='PUBLISHED',price_unit='PERSON_MONTH' WHERE id=?", ids.get(0));
        assertTrue(value.find(input).candidateIds().isEmpty(), "No per-person/monthly comparison without a comparable group");
    }

    private Set<String> kindWithin(String categoryId, int meters) {
        return truth("EXISTS (SELECT 1 FROM listing_nearby_places nl JOIN nearby_places np ON np.id = nl.place_id AND np.active"
                + " WHERE nl.listing_id = l.id AND nl.straight_distance_m <= ? AND EXISTS (SELECT 1 FROM nearby_place_sources s"
                + " WHERE s.place_id = np.id AND jsonb_exists(s.classifications -> 'verifiedCategoryParents', ?)))", meters, categoryId);
    }

    /** The application's real scan fingerprint and explicit properties; the key is a placeholder and no provider is built. */
    private NearbySettings offlineNearbySettings() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("hs-api-service/src/main/resources/application.properties"))) root = root.getParent();
        assertNotNull(root);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(root.resolve("hs-api-service/src/main/resources/application.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        properties.setProperty("TOMTOM_API_KEY", "offline-ai-search-test-only");
        properties.setProperty("nearby.query-enabled", "true");
        properties.setProperty("nearby.tomtom.license-approved", "true");
        try (var context = new AnnotationConfigApplicationContext()) {
            var sources = context.getEnvironment().getPropertySources();
            sources.remove("systemProperties");
            sources.remove("systemEnvironment");
            sources.addFirst(new PropertiesPropertySource("offline-ai-search-test-configuration", properties));
            context.registerBean(PropertySourcesPlaceholderConfigurer.class);
            context.register(NearbySettings.class);
            context.refresh();
            return context.getBean(NearbySettings.class);
        }
    }
}
