package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real cached names + newly compiled resolver, enforced read-only, no Spring startup/provider. */
@EnabledIfEnvironmentVariable(named="HS_NEARBY_READONLY_PG_URL",matches="jdbc:postgresql://[^/]+/homespace_core")
class NearbyTargetReadOnlySmokeTest {
 @Test void resolveReportedNamesAndReadMatchingPublicListingsWithoutAnyWritesOrProviderCalls()throws Exception {
  var settings=NearbyConfigurationTest.configured();
  settings.setQueryEnabled(true);settings.setLicenseApproved(true);
  settings.setLicensePolicyId(NearbyConfigurationTest.applicationProperties().getProperty("nearby.tomtom.license-policy-id"));
  try(var connection=DriverManager.getConnection(System.getenv("HS_NEARBY_READONLY_PG_URL"),
      System.getenv("HS_NEARBY_READONLY_PG_USER"),System.getenv("HS_NEARBY_READONLY_PG_PASSWORD"))) {
   connection.setReadOnly(true);
   var source=new SingleConnectionDataSource(connection,true);
   var jdbc=new NamedParameterJdbcTemplate(source);
   var manager=new DataSourceTransactionManager(source);
   var repo=new NearbyRepository(jdbc,manager,settings);
   var query=new NearbyQueryService(repo,settings);
   var tx=new TransactionTemplate(manager);tx.setReadOnly(true);
   var rows=tx.execute(status->{
    assertEquals("on",jdbc.getJdbcTemplate().queryForObject("SHOW transaction_read_only",String.class));
    List<Map<String,Object>> results=new ArrayList<>();
    for(String keyword:List.of("Chi Nhánh Đại Học Ngân Hàng","Đại học công nghiệp HCM","ĐH Công nghiệp TP.HCM",
        "Trường Đại Học Công Nghiệp Thành Phố Hồ Chí Minh — 12 Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh")) {
     var targets=query.targets(keyword);assertEquals(1,targets.size(),keyword);
     var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
     String predicate=query.searchPredicate(targets.getFirst().id(),null,2000,params);
     Long total=jdbc.queryForObject("SELECT count(*) FROM listings l WHERE l.active=true AND l.status='PUBLISHED'"
       +" AND (l.expires_at IS NULL OR l.expires_at>now())"+predicate,params,Long.class);
     assertTrue(total>0,keyword);
     var linked=new HashSet<>(jdbc.queryForList("SELECT listing_id FROM listing_nearby_places WHERE place_id=:id",
       Map.of("id",targets.getFirst().id()),String.class));
     var service=new com.hs.listing.service.AiListingSearchService(jdbc);org.springframework.test.util.ReflectionTestUtils.setField(service,"nearbyQuery",query);
     var conditions=List.of(
       com.hs.listing.dto.request.AiListingSearchRequest.Condition.leaf("category","eq","ROOM"),
       com.hs.listing.dto.request.AiListingSearchRequest.Condition.leaf("rent.monthly_amount","lt","4500000"),
       com.hs.listing.dto.request.AiListingSearchRequest.Condition.leaf("occupancy.max_occupants","gte","3"));
     var filter=com.hs.listing.dto.request.AiListingSearchRequest.Condition.allOf(conditions);
     var nearby=List.of(new com.hs.listing.dto.request.AiListingSearchRequest.NearbyCriteria(targets.getFirst().id(),null,2000));
     var context=new com.hs.listing.dto.request.AiListingSearchRequest.SearchContext(3,null,null,null,null);
     var response=service.search(new com.hs.listing.dto.request.AiListingSearchRequest(null,"newest",4,null,null,2,filter,nearby,context,null,null,true));
     Long expected=jdbc.queryForObject("SELECT count(*) FROM listings l WHERE l.active AND l.status='PUBLISHED'"
       +" AND (l.expires_at IS NULL OR l.expires_at>now()) AND l.category='ROOM'"
       +" AND (CASE WHEN l.price_unit IN ('MONTH','ROOM_MONTH') THEN l.price_amount WHEN l.price_unit='PERSON_MONTH' THEN l.price_amount*3 END)<4500000"
       +" AND EXISTS(SELECT 1 FROM listing_room_details d WHERE d.listing_id=l.id AND d.max_occupants>=3)"+predicate,params,Long.class);
     assertEquals(expected,response.total(),"Listing filters must be evaluated inside the exact POI's linked set: "+keyword);
     // An otherwise valid POI may legitimately have no linked rooms satisfying the price/occupancy filters.
     if(keyword.equals("Chi Nhánh Đại Học Ngân Hàng"))assertTrue(response.total()>0,"Reported regression must exercise real matching rooms");
     assertTrue(linked.containsAll(response.listingIds()),"Every result must link to the exact POI");
     for(String id:response.listingIds()) {
      boolean visible=false;
      for(int page=1;page<=20&&!visible;page++) {
       var detail=query.places(id,2000,null,page,5);
       visible=detail.items().stream().anyMatch(p->p.id().equals(targets.getFirst().id()));
       if(page*5>=detail.total())break;
      }
      assertTrue(visible,"The POI must also appear on the listing's public nearby detail: "+id);
     }
     var next=service.search(new com.hs.listing.dto.request.AiListingSearchRequest(null,"newest",4,null,null,2,filter,nearby,context,null,response.listingIds(),true));
     assertEquals(response.matchedTotal(),next.matchedTotal());
     assertTrue(Collections.disjoint(response.listingIds(),next.listingIds()));
     assertTrue(linked.containsAll(next.listingIds()));
     results.add(Map.of("query",keyword,"target",targets.getFirst(),"linkedPublicListingsWithin2km",total,
       "matchedAfterRoomPriceAndOccupantFilters",response.total(),"firstPage",response.listingIds(),"nextPage",next.listingIds(),
       "allReturnedListingsHaveExactPoiOnDetail",true));
    }
    return results;
   });
   String report=System.getenv("HS_NEARBY_READONLY_REPORT");
   if(report!=null)Files.writeString(Path.of(report),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
   System.out.println("Cached POI resolver: "+rows.size()+" real-query cases passed; DB transaction read-only; provider requests=0");
  }
 }
}
