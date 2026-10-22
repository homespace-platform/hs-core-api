package com.hs.listing.service.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.listing.config.ListingPolicyInitializer;
import com.hs.listing.config.ListingReferenceDataInitializer;
import com.hs.listing.repository.AmenityRepository;
import com.hs.listing.repository.FurnishingItemRepository;
import com.hs.listing.repository.ListingPolicyDefinitionRepository;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.repository.ListingAddressRepository;
import com.hs.listing.dto.request.PublicListingSearchRequest;
import com.hs.listing.service.ListingPublicService;
import com.hs.listing.service.AiListingSearchService;
import com.hs.storage.config.StorageProperties;
import com.hs.user.repository.UserRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Destructive schema creation is opt-in and restricted to a disposable database named hs_seed_test. */
@EnabledIfEnvironmentVariable(named="HS_SEED_TEST_PG_URL", matches="jdbc:postgresql://[^/]+/hs_seed_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=ListingSeedPostgresTest.Config.class)
@TestPropertySource(properties={
    "spring.datasource.url=${HS_SEED_TEST_PG_URL}",
    "spring.datasource.username=postgres", "spring.datasource.password=seed-test-only",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ListingSeedPostgresTest {
    @SpringBootConfiguration @EntityScan("com.hs")
    @EnableJpaRepositories(basePackageClasses=ListingRepository.class)
    static class Config {}

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AmenityRepository amenities;
    @Autowired FurnishingItemRepository furnishings;
    @Autowired ListingPolicyDefinitionRepository policies;
    @Autowired ListingRepository listings;
    @Autowired ListingAddressRepository addresses;

    private void execute(String sql) throws SQLException {
        try (var connection=dataSource.getConnection(); var statement=connection.createStatement()) {
            connection.setAutoCommit(true);
            try { statement.execute(sql); }
            catch (SQLException failure) { statement.execute("ROLLBACK"); throw failure; }
        }
    }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table,Long.class); }
    private String counts() {
        return List.of("listings","listing_addresses","listing_house_details","listing_apartment_details",
            "listing_room_details","listing_charges","listing_furnishing_assets","listing_amenities",
            "listing_policy_values","listing_media","listing_viewing_days","listing_viewing_slots").stream()
            .map(table -> table + ":" + count(table)).toList().toString();
    }

    @Test void preservesEveryPointAndNullAgainstTheActualHibernateSchemaAndRerunsSafely() throws Exception {
        Path sqlPath=Path.of(System.getProperty("hs.seed.sql")).toAbsolutePath();
        String sql=Files.readString(sqlPath);
        JsonNode source=new ObjectMapper().readTree(Files.readString(sqlPath.resolveSibling("homespace_seed_300_duong_govap.json")));
        assertEquals(300,source.size());
        // Fresh mapping permits unknown metadata on listings only, not user/branch addresses.
        assertEquals(3L,jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name='listing_addresses' and column_name in ('ref_id','ward_id','resolved_at') and is_nullable='YES'",Long.class));
        assertEquals(6L,jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name in ('user_addresses','branch_addresses') and column_name in ('ref_id','ward_id','resolved_at') and is_nullable='NO'",Long.class));
        // Seed directly into the fresh Hibernate schema, without any SQL schema fix.
        SQLException noOwner=assertThrows(SQLException.class,() -> execute(sql));
        assertTrue(noOwner.getMessage().contains("tài khoản"));
        assertEquals(0,count("listings"));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            new ListingReferenceDataInitializer(amenities,furnishings).run();
            new ListingPolicyInitializer(policies).run();
        });
        execute("INSERT INTO roles(id,active,name) VALUES ('seed-test-role',true,'ADMIN'); "
            + "INSERT INTO users(id,active,email,on_boarded,username,role_id) VALUES ('seed-test-owner',true,'seed@example.invalid',true,'homespace','seed-test-role');");
        execute(sql);
        assertEquals(300,count("listings")); assertEquals(300,count("listing_addresses"));
        assertEquals(900,count("listing_media"));
        assertEquals(0L,jdbc.queryForObject("select count(*) from listings where title like '%[DEMO]%' or title like '%· điểm %' or description like '%Tin mô phỏng%'",Long.class));
        assertTrue(jdbc.queryForObject("select count(distinct title) from listings",Long.class)>=270,"Natural titles should have substantial variety");
        assertEquals(300L,jdbc.queryForObject("select count(*) from listings where status_reason like 'DEMO%'",Long.class),"Synthetic provenance remains in internal metadata");
        assertEquals(0L,jdbc.queryForObject("select count(*) from listings where description not like '%' || area_m2::int || ' m²%'",Long.class),"Description area matches the listing column");
        assertEquals(0L,jdbc.queryForObject("select count(*) from listings where (category='HOUSE' and (price_amount%500000<>0 or price_amount not between 8000000 and 35000000)) or (category='APARTMENT' and (price_amount%250000<>0 or price_amount not between 4000000 and 18000000)) or (category='ROOM' and (price_amount%100000<>0 or price_amount not between 2000000 and 5000000))",Long.class));
        assertEquals(0L,jdbc.queryForObject("select count(*) from listings l join listing_house_details h on h.listing_id=l.id where h.bathroom_count<2 or h.bedroom_count<2 or l.area_m2>h.land_area_m2*h.total_floors",Long.class));
        assertEquals(0L,jdbc.queryForObject("select count(*) from listings l join listing_apartment_details a on a.listing_id=l.id where (a.bedroom_count=0 and l.area_m2>45) or (a.bedroom_count>=3 and l.area_m2<80)",Long.class));
        assertEquals(0L,jdbc.queryForObject("select count(*) from listings where view_count<=0 or created_at>published_at or published_at>now()",Long.class));
        System.out.println("SEED QUALITY: unique titles=" + jdbc.queryForObject("select count(distinct title) from listings",Long.class));
        for (var sample:jdbc.queryForList("select l.title,l.price_amount,l.area_m2 from listings l join listing_addresses a on a.listing_id=l.id order by a.id limit 6"))
            System.out.println("SEED SAMPLE: " + sample);
        for (String category:List.of("HOUSE","APARTMENT","ROOM"))
            assertEquals(100L,jdbc.queryForObject("select count(*) from listings where category=?",Long.class,category));
        for (JsonNode point:source) {
            var address=jdbc.queryForMap("select * from listing_addresses where id=?",point.path("id").asText());
            assertEquals(0,new BigDecimal(point.path("latitude").asText()).compareTo((BigDecimal)address.get("latitude")));
            assertEquals(0,new BigDecimal(point.path("longitude").asText()).compareTo((BigDecimal)address.get("longitude")));
            for (var entry:java.util.Map.of("ref_id","refId","house_number","houseNumber",
                    "district_id","districtId","district","district","resolved_at","resolvedAt",
                    "created_by","createdBy","updated_by","updatedBy").entrySet()) {
                assertTrue(point.path(entry.getValue()).isNull()); assertNull(address.get(entry.getKey()));
            }
            assertEquals(point.path("display").asText(),address.get("display"));
            assertEquals(point.path("street").asText(),address.get("street"));
            assertEquals(point.path("ward").asText(),address.get("ward"));
            assertEquals(point.path("cityId").asText(),address.get("city_id"));
            String expectedWard=switch (point.path("ward").asText()) {
                case "Phường Gò Vấp" -> "24588";
                case "Phường An Nhơn" -> "24332";
                case "Phường Hạnh Thông" -> "24076";
                default -> throw new AssertionError("Unknown test ward");
            };
            assertEquals(expectedWard,address.get("ward_id"));
        }
        verifyPublicFilters();
        verifyDenseWardAiSearch();
        // Re-running also repairs the previous seed version's missing IDs, without replacing source coordinates.
        execute("UPDATE listing_addresses SET ward_id=NULL");
        // Unrelated and legacy-seed listing IDs must survive all reruns unchanged.
        execute("INSERT INTO listings SELECT (jsonb_populate_record(NULL::listings, to_jsonb(l) || jsonb_build_object('id','unrelated-listing','title','DO NOT TOUCH'))).* FROM listings l LIMIT 1");
        execute("INSERT INTO listings SELECT (jsonb_populate_record(NULL::listings, to_jsonb(l) || jsonb_build_object('id',md5('homespace-hcm-200-listing-v2-1')::uuid::text,'title','OLD SEED KEEP'))).* FROM listings l LIMIT 1");
        String before=counts(); execute(sql); assertEquals(before,counts());
        verifyPublicFilters();
        assertEquals("DO NOT TOUCH",jdbc.queryForObject("select title from listings where id='unrelated-listing'",String.class));
        assertEquals("OLD SEED KEEP",jdbc.queryForObject("select title from listings where id=md5('homespace-hcm-200-listing-v2-1')::uuid::text",String.class));
        // Force an error AFTER listing/address writes to prove the complete transaction rolls back.
        execute("DELETE FROM furnishing_item_categories WHERE category='ROOM'");
        SQLException badCatalog=assertThrows(SQLException.class,() -> execute(sql));
        assertTrue(badCatalog.getMessage().contains("nội thất"));
        assertEquals(before,counts());
        assertEquals(300L,jdbc.queryForObject("select count(*) from listing_addresses where ref_id is null and ward_id is not null and resolved_at is null and house_number is null",Long.class));
    }

    private void verifyPublicFilters() {
        var storage=new StorageProperties("seed-test","ap-southeast-1",java.time.Duration.ofMinutes(5),java.time.Duration.ofMinutes(5),null,null);
        var service=new ListingPublicService(listings,addresses,org.mockito.Mockito.mock(UserRepository.class),storage);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(300,service.search(searchRequest(java.util.Map.of("cityId","12"))).getTotalElements());
            for (var ward:java.util.Map.of("24588",142L,"24332",109L,"24076",49L).entrySet()) {
                var result=service.search(searchRequest(java.util.Map.of("cityId","12","wardId",ward.getKey())));
                assertEquals(ward.getValue().longValue(),result.getTotalElements());
                assertEquals((int)((ward.getValue()+49)/50),result.getTotalPages());
                assertTrue(result.getResult().stream().allMatch(item -> "12".equals(item.cityId()) && ward.getKey().equals(item.wardId())));
                var second=service.search(searchRequest(java.util.Map.of("cityId","12","wardId",ward.getKey(),"page",2)));
                var ids=result.getResult().stream().map(item -> item.id()).toList();
                assertTrue(second.getResult().stream().noneMatch(item -> ids.contains(item.id())),"Paging must not repeat results");
            }
            assertEquals(0,service.search(searchRequest(java.util.Map.of("cityId","11","wardId","24588"))).getTotalElements());
            assertEquals(0,service.search(searchRequest(java.util.Map.of("cityId","79"))).getTotalElements(),"Old province codes are not Vietmap IDs");
            assertEquals(0,service.search(searchRequest(java.util.Map.of("cityId","12","wardId","27076"))).getTotalElements(),"Do not match by old code or name fallback");
            for (String category:List.of("HOUSE","APARTMENT","ROOM")) {
                var result=service.search(searchRequest(java.util.Map.of("cityId","12","wardId","24588","category",category,
                    "priceMin",3000000,"priceMax",15000000,"areaMin",20,"areaMax",200,"sort","price_asc")));
                long expected=jdbc.queryForObject("select count(*) from listings l join listing_addresses a on a.listing_id=l.id where l.active and l.status='PUBLISHED' and a.active and a.city_id='12' and a.ward_id='24588' and l.category=? and l.price_amount between 3000000 and 15000000 and l.area_m2 between 20 and 200",Long.class,category);
                assertTrue(expected>0); assertEquals(expected,result.getTotalElements());
                assertTrue(result.getResult().stream().allMatch(item -> item.category().name().equals(category)));
            }
            assertTrue(service.search(searchRequest(java.util.Map.of("cityId","12","wardId","24588","keyword","Quang Trung"))).getTotalElements()>0);
        });
        System.out.println("FILTER PASS: city/3 wards, invalid/old codes, pagination, category/price/area/keyword against listing_addresses");
    }

    private PublicListingSearchRequest searchRequest(java.util.Map<String,Object> filters) {
        var values=new java.util.HashMap<String,Object>(); values.put("page",1); values.put("size",50); values.put("sort","newest"); values.putAll(filters);
        return new ObjectMapper().convertValue(values,PublicListingSearchRequest.class);
    }

    private void verifyDenseWardAiSearch() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.execute("""
                INSERT INTO listings
                SELECT (jsonb_populate_record(NULL::listings,to_jsonb(l) || jsonb_build_object(
                    'id',md5('ai-density-listing:' || n)::uuid::text,
                    'title','DENSITY FIXTURE ' || n,'price_amount',2000000+(n%20)*250000,
                    'area_m2',20+(n%20),'expires_at',null))).*
                FROM (SELECT l.* FROM listings l JOIN listing_addresses a ON a.listing_id=l.id
                      WHERE l.category='ROOM' AND a.ward_id='24588' LIMIT 1) l
                CROSS JOIN generate_series(1,1000) n
                """);
            jdbc.execute("""
                INSERT INTO listing_addresses
                SELECT (jsonb_populate_record(NULL::listing_addresses,to_jsonb(a) || jsonb_build_object(
                    'id',md5('ai-density-address:' || n)::uuid::text,
                    'listing_id',md5('ai-density-listing:' || n)::uuid::text))).*
                FROM (SELECT a.* FROM listing_addresses a WHERE a.ward_id='24588' LIMIT 1) a
                CROSS JOIN generate_series(1,1000) n
                """);
            var service=new AiListingSearchService(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(dataSource));
            var filters=List.of(new com.hs.listing.dto.request.AiListingSearchRequest.Filter("city_id","eq","12"),
                new com.hs.listing.dto.request.AiListingSearchRequest.Filter("ward_id","eq","24588"),
                new com.hs.listing.dto.request.AiListingSearchRequest.Filter("category","eq","ROOM"),
                new com.hs.listing.dto.request.AiListingSearchRequest.Filter("price_amount","lte",3000000),
                new com.hs.listing.dto.request.AiListingSearchRequest.Filter("area_m2","gte",22));
            var result=service.search(new com.hs.listing.dto.request.AiListingSearchRequest(filters,"price_asc",5));
            assertTrue(result.total()>100); assertEquals(5,result.listingIds().size());
            var ten=service.search(new com.hs.listing.dto.request.AiListingSearchRequest(filters,"price_asc",10));
            assertEquals(10,ten.listingIds().size()); assertEquals(result.total(),ten.total());
            var twenty=service.search(new com.hs.listing.dto.request.AiListingSearchRequest(filters,"price_asc",20));
            assertEquals(20,twenty.listingIds().size());
            for(String id:result.listingIds()) {
                var row=jdbc.queryForMap("select l.price_amount,l.area_m2,a.ward_id from listings l join listing_addresses a on a.listing_id=l.id where l.id=?",id);
                assertEquals("24588",row.get("ward_id"));
                assertTrue(((BigDecimal)row.get("price_amount")).compareTo(new BigDecimal("3000000"))<=0);
                assertTrue(((BigDecimal)row.get("area_m2")).compareTo(new BigDecimal("22"))>=0);
            }
            assertThrows(IllegalArgumentException.class,() -> service.search(
                new com.hs.listing.dto.request.AiListingSearchRequest(filters,"newest",1000)));
            System.out.println("DENSITY PASS: 1000 additional listings in one ward, all SQL criteria applied; requested/returned=5/5,10/10,20/20; total matches="+result.total());
            status.setRollbackOnly();
        });
        assertEquals(300,count("listings"));
    }
}
