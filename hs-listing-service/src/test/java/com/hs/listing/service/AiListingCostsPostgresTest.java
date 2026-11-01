package com.hs.listing.service;

import com.hs.listing.dto.request.AiListingCostRequest;
import com.hs.listing.dto.request.AiListingSearchRequest.SearchContext;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL on an explicitly disposable DB only. Does not touch Hibernate/dev schemas or providers. */
@EnabledIfEnvironmentVariable(named="HS_AI_SEARCH_TEST_PG_URL", matches="jdbc:postgresql://[^/]+/hs_ai_search_test")
class AiListingCostsPostgresTest {
    @Test void totalsMissingInputsPublicScopeAndPrecision() throws Exception {
        try (var c = DriverManager.getConnection(System.getenv("HS_AI_SEARCH_TEST_PG_URL"), "postgres", "ai-search-test-only")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
            String schema = "cost_test_" + UUID.randomUUID().toString().replace("-", "");
            jdbc.execute("CREATE SCHEMA " + schema);
            c.setSchema(schema);
            try {
                jdbc.execute("CREATE TABLE listings(id varchar primary key, active boolean, status varchar, expires_at timestamptz, price_amount numeric, price_unit varchar, category varchar, area_m2 numeric, max_motorbike_count int, max_car_count int)");
                jdbc.execute("CREATE TABLE listing_charges(listing_id varchar, active boolean, charge_type varchar, billing_method varchar, included_in_rent boolean, amount numeric)");
                jdbc.execute("CREATE TABLE listing_room_details(listing_id varchar, parking_policy varchar)");
                var ids = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
                int[] rents={3800000,3900000,3300000,3800000}, services={40000,30000,45000,30000}, internet={0,90000,120000,120000};
                for(int i=0;i<4;i++) {
                    jdbc.update("INSERT INTO listings VALUES (?,true,'PUBLISHED',null,?,'ROOM_MONTH','ROOM',30,2,0)",ids.get(i),rents[i]);
                    jdbc.update("INSERT INTO listing_room_details VALUES (?,'FREE')",ids.get(i));
                    jdbc.update("INSERT INTO listing_charges VALUES (?,true,'SERVICE_OR_GARBAGE','PER_PERSON_MONTH',false,?)",ids.get(i),services[i]);
                    jdbc.update("INSERT INTO listing_charges VALUES (?,true,'INTERNET','PER_MONTH',?,?)",ids.get(i),i==0,internet[i]);
                    jdbc.update("INSERT INTO listing_charges VALUES (?,true,'MOTORBIKE_PARKING','PER_VEHICLE_MONTH',?,80000)",ids.get(i),i!=0);
                    jdbc.update("INSERT INTO listing_charges VALUES (?,true,'ELECTRICITY','PER_KWH',false,3550.25)",ids.get(i));
                    jdbc.update("INSERT INTO listing_charges VALUES (?,true,'WATER','PER_M3',false,12000)",ids.get(i));
                    jdbc.update("INSERT INTO listing_charges VALUES (?,false,'OTHER','PER_MONTH',false,999999)",ids.get(i));
                }
                var service = new AiListingSearchService(new NamedParameterJdbcTemplate(jdbc));
                var unknown = service.costs(new AiListingCostRequest(ids, new SearchContext(3,null,null,null,null)));
                var first = unknown.items().stream().filter(r->r.listingId().equals(ids.get(0))).findFirst().orElseThrow();
                assertNull(first.fixedMonthlyTotal());
                assertEquals(List.of("motorbikes","electricityKwh","waterM3"), first.missingInputs());
                assertEquals(0, new BigDecimal("3920000").compareTo(first.knownFixedMonthly()));
                var full = service.costs(new AiListingCostRequest(ids, new SearchContext(3,1,0,100,8)));
                int[] fixed = {4000000,4080000,3555000,4010000};
                for(int i=0;i<4;i++) {
                    final String id=ids.get(i);
                    var row=full.items().stream().filter(r->r.listingId().equals(id)).findFirst().orElseThrow();
                    assertEquals(0,BigDecimal.valueOf(fixed[i]).compareTo(row.fixedMonthlyTotal()));
                    assertEquals(0,BigDecimal.valueOf(fixed[i]).add(new BigDecimal("451025")).compareTo(row.estimatedMonthlyTotal()));
                    assertTrue(row.missingInputs().isEmpty()); assertTrue(row.motorbikesAllowed());
                }
                assertFalse(service.costs(new AiListingCostRequest(List.of(ids.get(0)),new SearchContext(3,3,0,null,null))).items().getFirst().motorbikesAllowed());
                jdbc.update("UPDATE listings SET active=false WHERE id=?", ids.get(0));
                assertEquals(3, service.costs(new AiListingCostRequest(ids,null)).items().size());
                assertThrows(IllegalArgumentException.class,()->service.costs(new AiListingCostRequest(List.of("unsafe"),null)));
                assertThrows(IllegalArgumentException.class,()->service.costs(new AiListingCostRequest(List.of(ids.get(1),ids.get(1)),null)));
                assertThrows(IllegalArgumentException.class,()->service.costs(new AiListingCostRequest(ids,new SearchContext(0,null,null,null,null))));
                jdbc.update("UPDATE listings SET price_unit='PERSON_MONTH', price_amount=1000000 WHERE id=?",ids.get(1));
                assertNull(service.costs(new AiListingCostRequest(List.of(ids.get(1)),null)).items().getFirst().monthlyRent());
            } finally {
                c.setSchema("public");
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
