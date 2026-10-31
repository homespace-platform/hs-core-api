package com.hs.listing.nearby;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hs.listing.nearby.NearbyTypes.*;

/** Explicitly opt in to a disposable database, never homespace_core. */
@EnabledIfEnvironmentVariable(named="HS_NEARBY_TEST_PG_URL",matches="jdbc:postgresql://[^/]+/hs_nearby_test")
@DataJpaTest(showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=NearbyPostgresTest.Config.class)
@TestPropertySource(properties={
 "spring.datasource.url=${HS_NEARBY_TEST_PG_URL}",
 "spring.datasource.username=nearby_test", "spring.datasource.password=nearby_test",
 "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.open-in-view=false",
 "spring.jpa.properties.hibernate.hbm2ddl.halt_on_error=true"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class NearbyPostgresTest {
 @SpringBootConfiguration
 @EntityScan("com.hs")
 @EnableJpaRepositories(basePackageClasses=NearbyCategoryRepository.class)
 static class Config {}
 @Autowired DataSource dataSource;
 @Autowired NearbyCategoryRepository categories;
 @Autowired EntityManagerFactory entityManagerFactory;
 static final String A="00000000-0000-0000-0000-000000000001",B="00000000-0000-0000-0000-000000000002";
 NamedParameterJdbcTemplate jdbc;NearbySettings settings;NearbyRepository repo;NearbyQueryService query;
 @BeforeEach void setup()throws Exception {
  jdbc=new NamedParameterJdbcTemplate(dataSource);settings=NearbyConfigurationTest.configured();
  settings.setLicenseApproved(true);settings.setLicensePolicyId("offline-test-only");settings.setQueryEnabled(true);
  repo=new NearbyRepository(jdbc,new DataSourceTransactionManager(dataSource),settings);query=new NearbyQueryService(repo,settings);
  new NearbyCategoryInitializer(categories).run();
  jdbc.getJdbcTemplate().execute("TRUNCATE listings CASCADE");
  jdbc.getJdbcTemplate().execute("TRUNCATE nearby_places CASCADE");
  for(String id:List.of(A,B)) {
   jdbc.update("""
    INSERT INTO listings(id,owner_id,active,title,description,category,status,available_from,area_m2,price_amount,
     currency,price_unit,negotiable,deposit_type,payment_cycle,minimum_lease_months,management_fee_included,view_count,version,
     published_at,status_changed_at)
    VALUES(:id,:id,true,'Room','', 'ROOM','PUBLISHED',current_date,25,3000000,
     'VND','MONTH',false,'NONE','MONTHLY',1,false,0,0,now(),now())
    """,Map.of("id",id));
   jdbc.update("""
    INSERT INTO listing_addresses(id,listing_id,active,latitude,longitude,updated_at,display,street,address,city_id,city,ward,version)
    VALUES(:address,:id,true,:lat,106.6872986,now(),'Fixture','Fixture','Fixture','12','Hồ Chí Minh','Hạnh Thông',0)
    """,
    Map.of("address",UUID.randomUUID().toString(),"id",id,"lat",id.equals(A)?10.8209195:10.8309195));
  }
 }
 NearbyProvider fake(boolean complete) {
  return new NearbyProvider(){
   public Map<String,Set<String>> categoryParents() {
    Map<String,Set<String>> m=new HashMap<>();
    for(String id:List.of("7372","7377","9913","7321","7326","9373","9374","9663","9956","7391","7332","9361","7373","7327",
      "7315","9376","7328","7397","9942","7311","7309","7369","7313","7380","9362","7342","7317","7318","7319","9378","9900","9902","9927","7376","7305","7320","7338","7374","9369","9911","9371"))m.put(id,Set.of());
    m.put("7372010",Set.of("7372"));m.put("7372005",Set.of("7372"));
    m.put("7332005",Set.of("7332"));m.put("9361009",Set.of("9361"));m.put("9942002",Set.of("9942"));return m;
   }
   public Page search(Location l,int r,Set<String> c,int offset) {
    try{Page p=TomTomNearbyClient.parse(NearbyParserTest.fixture(),0);
     return c.contains("7372")?new Page(0,5,complete?5:100,p.places(),0):new Page(0,0,0,List.of(),0);
    }catch(Exception e){throw new RuntimeException(e);}
   }
  };
 }
 void scanBoth(boolean complete){
  repo.reconcile(3000,25);NearbyWorker worker=new NearbyWorker(settings,repo,fake(complete),new NearbyCatalog());
  Job j;while((j=repo.claim())!=null)worker.process(j);
 }
 long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Map.of(),Long.class);}
 long links(String listing){return jdbc.queryForObject("SELECT count(*) FROM listing_nearby_places WHERE listing_id=:id",Map.of("id",listing),Long.class);}
 void onlyA(){jdbc.update("UPDATE listings SET status='HIDDEN' WHERE id=:id",Map.of("id",B));}
 void due(Job job){jdbc.update("UPDATE nearby_scan_jobs SET next_attempt_at=now()-interval '1 second' WHERE id=:id",Map.of("id",job.id()));}
 List<Poi> extraPois(Location l){return List.of(
  new Poi("new-bank","Bank",l.latitude()+0.001,l.longitude(),null,null,Set.of("7328"),null,null,null),
  new Poi("new-pharmacy","Pharmacy and school",l.latitude()+0.002,l.longitude(),null,null,Set.of("7326","7372"),null,null,null));}
 @Test void combinedQueryUsesExactlyTenCategoriesAndCreatesOneRunPerListing(){
  var calls=new ArrayList<String>();NearbyProvider base=fake(true);
  NearbyProvider combined=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){
    assertEquals("7372,7377,7321,7326,7332005,9361009,7315,7328,9942002,9362",String.join(",",c));
    assertEquals(0,offset);calls.add(l.listingId());return base.search(l,r,c,offset);
   }
  };
  repo.reconcile(2000,25);var worker=new NearbyWorker(settings,repo,combined,new NearbyCatalog());
  Job job;while((job=repo.claim())!=null)worker.process(job);
  assertEquals(2,calls.size());assertEquals(2,count("nearby_scan_runs"));
  assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));assertEquals(10,count("listing_nearby_places"));
  var summary=query.summaries(new BatchRequest(List.of(A),2000,null,1)).getFirst();
  assertEquals(7,summary.groups().size());
  assertTrue(summary.groups().stream().allMatch(g->g.coverageStatus().equals("COMPLETE_PROVIDER_RESPONSE")));
  assertEquals("NOT_REQUESTED",query.summaries(new BatchRequest(List.of(A),2000,Set.of(Group.SPORTS),1)).getFirst().groups().getFirst().coverageStatus());
 }
 @Test void emptyAreaFinishesAfterOneRequestAndDoesNotSearchForMissingGroups(){
  onlyA();NearbyProvider base=fake(true);var calls=new ArrayList<Integer>();
  NearbyProvider empty=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){calls.add(offset);return new Page(0,0,0,List.of(),0);}
  };
  repo.reconcile(2000,25);new NearbyWorker(settings,repo,empty,new NearbyCatalog()).process(repo.claim());
  assertEquals(List.of(0),calls);assertEquals(0,links(A));
  var summary=query.summaries(new BatchRequest(List.of(A),2000,null,1)).getFirst();
  assertTrue(summary.groups().stream().allMatch(g->g.knownCount()==0&&g.coverageStatus().equals("COMPLETE_PROVIDER_RESPONSE")));
  repo.reconcile(2000,25);assertNull(repo.claim());
 }
 @Test void onePoiWithMultipleCategoriesIsLinkedToEachGroupWithoutCopyingThePoi(){
  onlyA();NearbyProvider base=fake(true);
  NearbyProvider mixed=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){return new Page(0,2,2,extraPois(l),0);}
  };
  repo.reconcile(2000,25);new NearbyWorker(settings,repo,mixed,new NearbyCatalog()).process(repo.claim());
  assertEquals(2,count("nearby_places"));assertEquals(2,links(A));
  assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM nearby_place_categories c JOIN nearby_place_sources s ON s.place_id=c.place_id WHERE s.external_id='new-pharmacy'",Map.of(),Long.class));
 }
 @Test void oneRequestStoresAtMostOneHundredWithoutFillingMissingCategories(){
  onlyA();NearbyProvider base=fake(true);var offsets=new ArrayList<Integer>();
  NearbyProvider capped=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){
    assertEquals(settings.categoryIds(),c);assertEquals(0,offset);offsets.add(offset);
    var pois=new ArrayList<Poi>();
    for(int i=0;i<100;i++)pois.add(new Poi("single-"+i,"Place "+i,l.latitude()+i*0.000001,l.longitude(),null,null,
     Set.of(i<95?"7315":"7326"),null,null,null));
    return new Page(0,100,900,pois,0);
   }
  };
  repo.reconcile(2000,25);var worker=new NearbyWorker(settings,repo,capped,new NearbyCatalog());Job job=repo.claim();worker.process(job);
  assertEquals(List.of(0),offsets);assertEquals(100,links(A));assertEquals(100,count("nearby_places"));assertEquals(1,count("nearby_scan_runs"));
  var summary=query.summaries(new BatchRequest(List.of(A),2000,null,1)).getFirst();
  assertTrue(summary.groups().stream().allMatch(g->g.coverageStatus().equals("LIMITED_PROVIDER_RESPONSE")));
  assertEquals(0,summary.groups().stream().filter(g->g.code()==Group.EDUCATION).findFirst().orElseThrow().knownCount());
  assertEquals(0,summary.groups().stream().filter(g->g.code()==Group.SHOPPING).findFirst().orElseThrow().knownCount());
  assertEquals(95,summary.groups().stream().filter(g->g.code()==Group.FOOD_DRINK).findFirst().orElseThrow().knownCount());
  assertEquals(5,query.places(A,2000,null,1,null).items().size());assertEquals(100,query.places(A,2000,null,1,null).total());
  assertEquals("SUCCESS",jdbc.queryForObject("SELECT status FROM nearby_scan_jobs WHERE id=:id",Map.of("id",job.id()),String.class));
  assertEquals(1L,jdbc.queryForObject("SELECT pages_fetched::bigint FROM nearby_scan_runs",Map.of(),Long.class));
  repo.reconcile(2000,25);assertNull(repo.claim());assertEquals(1,offsets.size());
 }
 @Test void quotaCanRetryAFailedRequestButNeverFetchesANextPageAfterSuccess(){
  onlyA();NearbyProvider base=fake(true);var offsets=new ArrayList<Integer>();
  NearbyProvider transientFailure=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){
    offsets.add(offset);if(offsets.size()==1)throw new Failure("QUOTA_MINUTE",true,60);
    return new Page(0,5,500,base.search(l,r,c,0).places(),0);
   }
  };
  repo.reconcile(2000,25);var worker=new NearbyWorker(settings,repo,transientFailure,new NearbyCatalog());
  Job first=repo.claim();worker.process(first);due(first);worker.process(repo.claim());
  assertEquals(List.of(0,0),offsets);assertEquals(1,count("nearby_scan_runs"));assertEquals(5,links(A));
  assertEquals("SUCCESS",jdbc.queryForObject("SELECT status FROM nearby_scan_jobs WHERE id=:id",Map.of("id",first.id()),String.class));
  repo.reconcile(2000,25);assertNull(repo.claim());
 }
 @Test void duplicateItemsInOneResponseAreDeduplicatedWithoutRequestingReplacements(){
  onlyA();NearbyProvider base=fake(true);var calls=new ArrayList<Integer>();
  NearbyProvider repeated=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){
    calls.add(offset);var pois=new ArrayList<>(base.search(l,r,c,0).places());pois.add(pois.getFirst());
    return new Page(0,6,200,pois,0);
   }
  };
  repo.reconcile(2000,25);new NearbyWorker(settings,repo,repeated,new NearbyCatalog()).process(repo.claim());
  assertEquals(List.of(0),calls);assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));assertEquals(5,links(A));
  assertEquals("PARTIAL",query.summaries(new BatchRequest(List.of(A),2000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
 }
 @Test void unknownCategoryIsStoredWithoutGuessingItsGroupOrReportingCompleteCoverage(){
  onlyA();NearbyProvider base=fake(true);
  NearbyProvider unknown=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){return new Page(0,1,1,List.of(
    new Poi("unknown-category","Unknown school-like label",l.latitude(),l.longitude(),null,null,Set.of("737299999"),null,null,null)),0);}
  };
  repo.reconcile(2000,25);new NearbyWorker(settings,repo,unknown,new NearbyCatalog()).process(repo.claim());
  assertEquals(1,count("nearby_places"));assertEquals(1,links(A));assertEquals(0,count("nearby_place_categories"));
  assertEquals("PARTIAL",query.summaries(new BatchRequest(List.of(A),2000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
 }
 @Test void changingQueryScopeCannotReuseOldJobsOrEvidence(){
  scanBoth(true);settings.setCategorySet("7372");assertNull(repo.claim());
  assertEquals(0,query.places(A,3000,null,1,5).total());
  assertEquals("NOT_READY",query.summaries(new BatchRequest(List.of(A),3000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
  repo.reconcile(3000,25);assertNotNull(repo.claim());assertEquals(4,count("nearby_scan_jobs"));
 }
 @Test void aSavedResponseIsFinalizedAfterACrashWithoutAnotherProviderRequest(){
  onlyA();NearbyProvider base=fake(true);repo.reconcile(2000,25);Job job=repo.claim();Location l=repo.locations(List.of(A)).getFirst();
  var catalog=new NearbyCatalog();String run=repo.startRun(job,l,catalog.requestedGroups(settings.categoryIds()),settings.categoryIds());
  repo.savePage(job,l,run,new Page(0,5,500,base.search(l,2000,settings.categoryIds(),0).places(),0),base.categoryParents(),catalog,true);
  NearbyProvider noMoreRequests=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location center,int r,Set<String> c,int offset){throw new AssertionError("Already stored the response");}
  };
  new NearbyWorker(settings,repo,noMoreRequests,catalog).process(job);
  assertEquals(1,count("nearby_scan_runs"));assertEquals(5,links(A));
  assertEquals("LIMITED_PROVIDER_RESPONSE",query.summaries(new BatchRequest(List.of(A),2000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
 }
 @Test void limitedRefreshReplacesOnlyThisListingsLinksAndKeepsTheSharedPoiWarehouse(){
  scanBoth(true);NearbyProvider base=fake(true);
  NearbyProvider refreshed=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int r,Set<String> c,int offset){return new Page(0,1,500,List.of(base.search(l,r,c,0).places().getFirst()),0);}
  };
  repo.enqueue(A,3000);new NearbyWorker(settings,repo,refreshed,new NearbyCatalog()).process(repo.claim());
  assertEquals(1,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));
  assertEquals(1,query.places(A,3000,null,1,5).total());assertEquals(5,query.places(B,3000,null,1,5).total());
  assertEquals("LIMITED_PROVIDER_RESPONSE",query.summaries(new BatchRequest(List.of(A),3000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
 }
 @Test void hibernateCreatesNearbyTablesAndReferenceGroupsWithoutExternalDdl(){
  repo.validateSchema();
  assertEquals(8,count("nearby_categories"));
  new NearbyCategoryInitializer(categories).run();
  assertEquals(8,count("nearby_categories"));
  assertEquals(7L,jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('nearby_places','nearby_place_sources','nearby_categories','nearby_place_categories','nearby_scan_jobs','nearby_scan_runs','listing_nearby_places')",Map.of(),Long.class));
 }
 @Test void javaEntitiesReadWorkerJsonAndCompositeKeysFromTheHibernateSchema(){
  scanBoth(true);
  String sourceId=jdbc.queryForObject("SELECT id FROM nearby_place_sources ORDER BY id LIMIT 1",Map.of(),String.class);
  try(var em=entityManagerFactory.createEntityManager()){
   var source=em.find(com.hs.listing.nearby.model.NearbyPlaceSource.class,sourceId);
   assertEquals("TOMTOM",source.getProvider());
   assertFalse(source.getCategoryIds().isEmpty());
   assertTrue(source.getClassifications().containsKey("verifiedCategoryParents"));
   assertEquals(7,source.getPlace().getLatitude().scale());
   var link=em.find(com.hs.listing.nearby.model.ListingNearbyPlace.class,
     new com.hs.listing.nearby.model.ListingNearbyPlaceId(A,source.getPlace().getId()));
   assertNotNull(link.getEvidenceRun());
   assertEquals("COMPLETE_PROVIDER_RESPONSE",link.getEvidenceRun().getCoverageStatus());
   assertFalse(link.getEvidenceRun().getPageSummaries().isEmpty());
   var category=em.find(com.hs.listing.nearby.model.NearbyPlaceCategory.class,
     new com.hs.listing.nearby.model.NearbyPlaceCategoryId(source.getPlace().getId(),"EDUCATION"));
   assertEquals("Giáo dục",category.getCategory().getDisplayNameVi());
  }
 }
 @Test void twoListingsReuseFivePlacesButHaveDifferentDistancesAndLimitedCoverage(){
  scanBoth(false);assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));assertEquals(10,count("listing_nearby_places"));
  var summaries=query.summaries(new BatchRequest(List.of(A,B),3000,Set.of(Group.EDUCATION),3));
  assertEquals("LIMITED_PROVIDER_RESPONSE",summaries.getFirst().groups().getFirst().coverageStatus());
  assertTrue(summaries.getFirst().groups().getFirst().nearest().getFirst().distanceMeters()<100);
  assertTrue(summaries.getLast().groups().getFirst().nearest().getFirst().distanceMeters()>900);
  repo.enqueue(A,3000);Job retry=repo.claim();new NearbyWorker(settings,repo,fake(false),new NearbyCatalog()).process(retry);
  assertEquals(5,count("nearby_places"));assertEquals(10,count("listing_nearby_places"));
 }
 @Test void completeThreeKmNeverClaimsFiveKmCoverageAndFiltersBeforeLimit(){
  scanBoth(true);
  assertEquals("COMPLETE_PROVIDER_RESPONSE",query.summaries(new BatchRequest(List.of(A),3000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
  assertEquals("PARTIAL",query.summaries(new BatchRequest(List.of(A),5000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
  var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
  String predicate=query.searchPredicate(null,Group.EDUCATION,100,params);
  var ids=jdbc.queryForList("SELECT l.id FROM listings l WHERE l.active=true AND l.status='PUBLISHED' "+predicate+" ORDER BY l.id LIMIT 10",params,String.class);
  assertEquals(List.of(A),ids);
 }
 @Test void cachedLegacyAddressesAreRebuiltFromComponentsForAllPublicProjections(){
  scanBoth(true);
  var target=query.targets("Thái Bình Dương").getFirst();
  jdbc.update("UPDATE nearby_places SET address_text='Legacy Phường 4, postal code 71423'",Map.of());
  String expected="8 Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh";
  assertEquals(expected,query.targets("Thái Bình Dương").getFirst().address());
  assertEquals(expected,query.places(A,null,null,1,20).items().stream().filter(p->p.id().equals(target.id())).findFirst().orElseThrow().address());
  var summary=query.summaries(new BatchRequest(List.of(A),null,Set.of(Group.EDUCATION),5,target.id())).getFirst();
  assertEquals(2000,summary.radiusRequestedMeters());
  assertEquals(expected,summary.groups().getFirst().nearest().stream().filter(p->p.id().equals(target.id())).findFirst().orElseThrow().address());
  assertTrue(jdbc.queryForObject("SELECT source_address->>'freeformAddress' FROM nearby_place_sources WHERE place_id=:id",Map.of("id",target.id()),String.class).contains("Phường 4"));
 }
 @Test void automaticScansUseTheConfiguredTwoKmRadius(){
  settings.setSyncEnabled(true);NearbyProvider base=fake(true);
  NearbyProvider provider=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int radius,Set<String> categories,int offset){assertEquals(2000,radius);return base.search(l,radius,categories,offset);}
  };
  NearbyWorker worker=new NearbyWorker(settings,repo,provider,new NearbyCatalog());worker.initialize();worker.tick();worker.tick();
  assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM nearby_scan_jobs WHERE status='SUCCESS' AND radius_m=2000",Map.of(),Long.class));
  assertEquals(5,query.places(A,null,null,1,20).total());
 }
 @Test void publicPaginationCapsAtFiveAndMatchesTheAiCategorySummary(){
  jdbc.update("UPDATE listings SET status='HIDDEN' WHERE id=:id",Map.of("id",B));
  NearbyProvider base=fake(true);
  NearbyProvider twelvePlaces=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int radius,Set<String> categories,int offset){
    Page page=base.search(l,radius,categories,offset);if(page.places().isEmpty())return page;
    var pois=new ArrayList<>(page.places());
    for(int i=1;i<=7;i++)pois.add(new Poi("pagination-"+i,"Pagination school "+i,l.latitude()+i*0.001,l.longitude(),null,null,Set.of("7372"),null,null,null));
    return new Page(0,pois.size(),pois.size(),pois,0);
   }
  };
  repo.reconcile(2000,25);new NearbyWorker(settings,repo,twelvePlaces,new NearbyCatalog()).process(repo.claim());
  var first=query.places(A,2000,null,1,null);
  var second=query.places(A,2000,null,2,20);
  var third=query.places(A,2000,null,3,20);
  assertEquals(12,first.total());assertEquals(5,first.size());assertEquals(5,first.items().size());
  assertEquals(12,second.total());assertEquals(5,second.size());assertEquals(5,second.items().size());
  assertEquals(5,third.size());assertEquals(2,third.items().size());
  Set<String> ids=new HashSet<>();first.items().forEach(p->assertTrue(ids.add(p.id())));second.items().forEach(p->assertTrue(ids.add(p.id())));
  third.items().forEach(p->assertTrue(ids.add(p.id())));
  assertEquals(12,ids.size());assertTrue(first.items().getLast().distanceMeters()<=second.items().getFirst().distanceMeters());
  assertTrue(query.places(A,2000,null,4,null).items().isEmpty());
  var categoryPage=query.places(A,2000,Set.of(Group.EDUCATION),1,5);
  var summary=query.summaries(new BatchRequest(List.of(A),2000,Set.of(Group.EDUCATION),5,null)).getFirst();
  assertEquals(categoryPage.items().stream().map(Place::id).toList(),summary.groups().getFirst().nearest().stream().map(Place::id).toList());
  assertEquals(5,summary.groups().getFirst().nearest().size());
 }
 @Test void movedCoordinatesAndHiddenListingsCannotLeakStaleEvidence(){
  scanBoth(true);jdbc.update("UPDATE listing_addresses SET latitude=11 WHERE listing_id=:id",Map.of("id",A));
  assertEquals(0,query.places(A,3000,null,1,20).total());
  jdbc.update("UPDATE listings SET status='HIDDEN' WHERE id=:id",Map.of("id",B));
  assertThrows(IllegalArgumentException.class,()->query.places(B,3000,null,1,20));
  assertEquals(5,count("nearby_places"));
 }
 @Test void addressChangeReplacesOnlyThatListingsRelationsAndReusesSharedPlaces(){
  scanBoth(true);
  var otherLinks=jdbc.queryForList("SELECT * FROM listing_nearby_places WHERE listing_id=:id ORDER BY place_id",Map.of("id",B));
  NearbyProvider base=fake(true);
  var fixture=base.search(repo.locations(List.of(A)).getFirst(),3000,Set.of("7372"),0).places();
  Poi reused=fixture.getFirst();
  Poi added=new Poi("new-place-after-move","New nearby school",10.8261195,106.6872986,
   null,null,Set.of("7372"),null,null,null);
  jdbc.update("UPDATE listing_addresses SET latitude=10.8259195,updated_at=now() WHERE listing_id=:id",Map.of("id",A));
  NearbyProvider moved=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int radius,Set<String> categories,int offset){
    assertEquals(A,l.listingId());assertEquals(10.8259195,l.latitude(),0.00000001);
    if(categories.contains("7372")){
     assertEquals(0,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));
     return new Page(0,2,2,List.of(reused,added),0);
    }
    return new Page(0,0,0,List.of(),0);
   }
  };
  settings.setSyncEnabled(true);
  NearbyWorker worker=new NearbyWorker(settings,repo,moved,new NearbyCatalog());
  worker.initialize();worker.changed(new ScanChanged(A));worker.tick();
  assertEquals(2,links(A));assertEquals(5,links(B));
  assertEquals(6,count("nearby_places"));assertEquals(6,count("nearby_place_sources"));
  assertEquals(otherLinks,jdbc.queryForList("SELECT * FROM listing_nearby_places WHERE listing_id=:id ORDER BY place_id",Map.of("id",B)));
  assertEquals(2,query.places(A,3000,null,1,20).total());assertEquals(5,query.places(B,3000,null,1,20).total());
  assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM nearby_scan_jobs WHERE listing_id=:id AND status='SUPERSEDED'",Map.of("id",A),Long.class));
 }
 @Test void failedFetchAfterMovingDoesNotRestoreOldLinksOrDeleteAnotherListingsPlaces(){
  scanBoth(true);NearbyProvider base=fake(true);
  jdbc.update("UPDATE listing_addresses SET latitude=10.8259195,updated_at=now() WHERE listing_id=:id",Map.of("id",A));
  repo.ensure(A,3000);Job changed=repo.claim();
  assertEquals(0,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));
  NearbyProvider unavailable=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int radius,Set<String> categories,int offset){throw new Failure("TOMTOM_HTTP_503",true);}
  };
  new NearbyWorker(settings,repo,unavailable,new NearbyCatalog()).process(changed);
  assertEquals("RETRY_WAIT",jdbc.queryForObject("SELECT status FROM nearby_scan_jobs WHERE id=:id",Map.of("id",changed.id()),String.class));
  assertEquals(0,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));
 }
 @Test void inFlightPageAtThePreviousAddressCannotRecreateRemovedRelations(){
  scanBoth(true);repo.enqueue(A,3000);Job old=repo.claim();
  Location previous=repo.locations(List.of(A)).getFirst();NearbyProvider provider=fake(true);
  String run=repo.startRun(old,previous,Set.of(Group.EDUCATION),Set.of("7372"));
  Page page=provider.search(previous,3000,Set.of("7372"),0);
  jdbc.update("UPDATE listing_addresses SET latitude=10.8259195,updated_at=now() WHERE listing_id=:id",Map.of("id",A));
  repo.enqueue(A,3000);
  assertEquals("SUPERSEDED",jdbc.queryForObject("SELECT status FROM nearby_scan_jobs WHERE id=:id",Map.of("id",old.id()),String.class));
  assertThrows(NearbyProvider.Failure.class,()->repo.savePage(old,previous,run,page,provider.categoryParents(),new NearbyCatalog()));
  assertEquals(0,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));
  new NearbyWorker(settings,repo,provider,new NearbyCatalog()).process(repo.claim());
  assertEquals(5,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));
 }
 @Test void movingBackToAnEarlierAddressReactivatesItsJobAndFetchesNewEvidence(){
  scanBoth(true);
  String initialHash=repo.locations(List.of(A)).getFirst().hash();
  jdbc.update("UPDATE listing_addresses SET latitude=10.8259195,updated_at=now() WHERE listing_id=:id",Map.of("id",A));
  repo.reconcile(3000,25);assertEquals(0,links(A));
  new NearbyWorker(settings,repo,fake(true),new NearbyCatalog()).process(repo.claim());
  jdbc.update("UPDATE listing_addresses SET latitude=10.8209195,updated_at=now() WHERE listing_id=:id",Map.of("id",A));
  repo.reconcile(3000,25);assertEquals(0,links(A));
  Job returned=repo.claim();assertNotNull(returned);assertEquals(initialHash,returned.locationHash());assertEquals(1,returned.attempts());
  assertEquals("STALE",query.summaries(new BatchRequest(List.of(A),3000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
  new NearbyWorker(settings,repo,fake(true),new NearbyCatalog()).process(returned);
  assertEquals(5,links(A));assertEquals(5,links(B));assertEquals(5,count("nearby_places"));
  assertEquals(3,count("nearby_scan_jobs"));assertNull(repo.claim());
 }
 @Test void expiredSourceIsExcludedAndPurgedWithoutDeletingListings(){
  settings.setRetentionHours(24);
  scanBoth(true);jdbc.update("UPDATE nearby_place_sources SET content_expires_at=now()-interval '1 second'",Map.of());
  assertEquals(0,query.places(A,3000,null,1,20).total());
  repo.purgeExpired();assertEquals(0,count("nearby_places"));assertEquals(0,count("listing_nearby_places"));assertEquals(2,count("listings"));
 }
 @Test void lostLeaseAndNewLocationCannotCommitOldResults() {
  repo.reconcile(3000,1);Job job=repo.claim();Location l=repo.locations(List.of(job.listingId())).getFirst();
  String run=repo.startRun(job,l,Set.of(Group.EDUCATION),Set.of("7372"));
  jdbc.update("UPDATE nearby_scan_jobs SET lease_token=:token WHERE id=:id",Map.of("token",UUID.randomUUID().toString(),"id",job.id()));
  assertThrows(NearbyProvider.Failure.class,()->repo.savePage(job,l,run,new Page(0,0,0,List.of(),0),Map.of(),new NearbyCatalog()));
 }
 @Test void disabledModuleDoesNotRequireMigratedTablesOrCallProvider(){
  settings.setQueryEnabled(false);
  assertFalse(query.places(A,3000,null,1,20).enabled());
  assertThrows(IllegalArgumentException.class,()->query.targets("IUH"));
 }
 @Test void exactTargetUsesCoordinatesEvenWhenItIsNotTheGenericNearestSchool(){
  scanBoth(true);String target=query.targets("Thái Bình Dương").getFirst().id();
  var summaries=query.summaries(new BatchRequest(List.of(A,B),100,Set.of(Group.EDUCATION),1,target));
  assertTrue(summaries.getFirst().target().withinRadius());
  assertFalse(summaries.getLast().target().withinRadius());
  assertTrue(summaries.getFirst().target().name().contains("Thái Bình Dương"));
 }
 @Test void cancelledJobCannotBeReclaimedOrWrittenByItsOldWorker(){
  repo.reconcile(3000,1);Job job=repo.claim();assertTrue(repo.cancel(job.id()));
  assertThrows(NearbyProvider.Failure.class,()->repo.heartbeat(job));
  assertNull(repo.claim());
 }
 @Test void concurrentScansDeduplicateSameSourceAndKeepBothListingRelations()throws Exception {
  repo.reconcile(3000,25);Job one=repo.claim(),two=repo.claim();assertNotEquals(one.id(),two.id());
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
   var worker=new NearbyWorker(settings,repo,fake(true),new NearbyCatalog());
   var a=pool.submit(()->worker.process(one));var b=pool.submit(()->worker.process(two));
   a.get(30,java.util.concurrent.TimeUnit.SECONDS);b.get(30,java.util.concurrent.TimeUnit.SECONDS);
  }
  assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));assertEquals(10,count("listing_nearby_places"));
 }
 @Test void automaticWorkerScansExistingPublicListingsWithoutAnActivationDateOrAdminAction(){
  settings.setSyncEnabled(true);
  jdbc.update("UPDATE listings SET published_at='2026-01-01',status_changed_at='2026-01-01'",Map.of());
  jdbc.update("UPDATE listing_addresses SET updated_at='2026-01-01'",Map.of());
  NearbyWorker worker=new NearbyWorker(settings,repo,fake(true),new NearbyCatalog());
  worker.initialize();worker.tick();worker.tick();
  assertEquals(2,count("nearby_scan_jobs"));
  assertEquals(2L,jdbc.queryForObject("SELECT count(*) FROM nearby_scan_jobs WHERE status='SUCCESS'",Map.of(),Long.class));
  assertEquals(5,count("nearby_places"));
  assertEquals(5,query.places(A,3000,null,1,20).total());
  assertEquals(5,query.places(B,3000,null,1,20).total());
  repo.reconcile(settings.getScanRadiusM(),25);assertEquals(2,count("nearby_scan_jobs"));
  assertNull(repo.claim());
 }
 @Test void reconciliationPrioritizesNewListingsAndSkipsUnavailableLocations(){
  jdbc.update("UPDATE listings SET published_at=now()-interval '30 days' WHERE id=:id",Map.of("id",A));
  repo.reconcile(3000,1);
  assertEquals(B,repo.claim().listingId());
  jdbc.update("UPDATE listings SET status='HIDDEN' WHERE id=:id",Map.of("id",A));
  repo.reconcile(3000,25);assertEquals(1,count("nearby_scan_jobs"));
  jdbc.update("UPDATE listings SET status='PUBLISHED',expires_at=now()-interval '1 second' WHERE id=:id",Map.of("id",A));
  repo.reconcile(3000,25);assertEquals(1,count("nearby_scan_jobs"));
  jdbc.update("UPDATE listings SET expires_at=NULL WHERE id=:id",Map.of("id",A));
  jdbc.update("UPDATE listing_addresses SET active=false WHERE listing_id=:id",Map.of("id",A));
  repo.reconcile(3000,25);assertEquals(1,count("nearby_scan_jobs"));
 }
 @Test void reconciliationAndPublicationHintsNeverRepeatCompletedOrPartialSnapshotsBecauseTimePassed(){
  scanBoth(true);
  repo.reconcile(3000,25);assertNull(repo.claim());
  jdbc.update("UPDATE nearby_scan_jobs SET next_attempt_at=now()-interval '30 days'",Map.of());
  repo.reconcile(3000,25);repo.ensure(A,3000);assertNull(repo.claim());
  jdbc.update("UPDATE nearby_scan_jobs SET status='PARTIAL'",Map.of());
  repo.reconcile(3000,25);repo.ensure(A,3000);assertNull(repo.claim());
  assertEquals(2,count("nearby_scan_jobs"));
 }
 @Test void snapshotsSurviveThirtyDaysAndRestartAndOnlyAChangedLocationFetchesAgain(){
  onlyA();settings.setSyncEnabled(true);assertEquals(0,settings.getRetentionHours());
  NearbyProvider base=fake(true);var calls=new java.util.concurrent.atomic.AtomicInteger();
  NearbyProvider provider=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int radius,Set<String> categories,int offset){
    calls.incrementAndGet();return base.search(l,radius,categories,offset);
   }
  };
  var worker=new NearbyWorker(settings,repo,provider,new NearbyCatalog());
  worker.initialize();worker.tick();assertEquals(1,calls.get());
  jdbc.update("UPDATE nearby_scan_jobs SET next_attempt_at=now()-interval '30 days'",Map.of());
  jdbc.update("UPDATE nearby_scan_runs SET expires_at=now()-interval '30 days',started_at=now()-interval '31 days',finished_at=now()-interval '31 days'",Map.of());
  jdbc.update("UPDATE nearby_place_sources SET content_expires_at=now()-interval '30 days',last_seen_at=now()-interval '31 days'",Map.of());
  repo.purgeExpired();assertEquals(5,count("nearby_places"));assertEquals(5,count("nearby_place_sources"));
  var restarted=new NearbyWorker(settings,repo,provider,new NearbyCatalog());
  restarted.initialize();restarted.tick();restarted.changed(new ScanChanged(A));restarted.tick();
  assertEquals(1,calls.get());assertNull(repo.claim());
  assertEquals(5,query.places(A,2000,null,1,5).total());
  String target=query.targets("Thái Bình Dương").getFirst().id();
  var summary=query.summaries(new BatchRequest(List.of(A),2000,Set.of(Group.EDUCATION),1,target)).getFirst();
  assertEquals("COMPLETE_PROVIDER_RESPONSE",summary.groups().getFirst().coverageStatus());
  assertNotNull(summary.target());
  var params=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
  String predicate=query.searchPredicate(target,null,2000,params);
  assertEquals(1L,jdbc.queryForObject("SELECT count(*) FROM listings l WHERE l.id='"+A+"'"+predicate,params,Long.class));
  jdbc.update("UPDATE listing_addresses SET latitude=10.8219195 WHERE listing_id=:id",Map.of("id",A));
  restarted.changed(new ScanChanged(A));restarted.tick();restarted.tick();
  assertEquals(2,calls.get());assertEquals(5,count("nearby_places"));assertEquals(5,links(A));
  assertNull(repo.claim());
 }
 @Test void providerPrecisionBeyondSevenDecimalsDoesNotInvalidateOtherListingDistances(){
  NearbyProvider base=fake(true);
  NearbyProvider precise=new NearbyProvider(){
   public Map<String,Set<String>> categoryParents(){return base.categoryParents();}
   public Page search(Location l,int radius,Set<String> categories,int offset){
    Page page=base.search(l,radius,categories,offset);
    var places=page.places().stream().map(p->new Poi(p.externalId(),p.name(),p.latitude()+0.0000000123,p.longitude()+0.0000000123,
      p.address(),p.info(),p.categoryIds(),p.classifications(),p.categoryLabels(),p.sourceAddress())).toList();
    return new Page(page.offset(),page.numResults(),page.totalResults(),places,page.malformedCount());
   }
  };
  repo.reconcile(3000,25);var worker=new NearbyWorker(settings,repo,precise,new NearbyCatalog());
  Job job;while((job=repo.claim())!=null)worker.process(job);
  assertEquals(0L,jdbc.queryForObject("SELECT max(position_revision) FROM nearby_places",Map.of(),Long.class));
  assertEquals(5,query.places(A,3000,null,1,20).total());
  assertEquals(5,query.places(B,3000,null,1,20).total());
 }
 @Test void movedSharedPoiMakesCoverageStaleAndExpiredRelationCannotReuseAnotherListingsSource(){
  settings.setRetentionHours(24);
  scanBoth(true);
  jdbc.update("UPDATE nearby_places SET latitude=latitude+0.001,position_revision=position_revision+1",Map.of());
  assertEquals(0,query.places(A,3000,null,1,20).total());
  assertEquals("STALE",query.summaries(new BatchRequest(List.of(A),3000,Set.of(Group.EDUCATION),1)).getFirst().groups().getFirst().coverageStatus());
  // Refreshing another listing renews the shared source, not this listing's old evidence.
  repo.enqueue(B,3000);new NearbyWorker(settings,repo,fake(true),new NearbyCatalog()).process(repo.claim());
  repo.enqueue(A,3000);new NearbyWorker(settings,repo,fake(true),new NearbyCatalog()).process(repo.claim());
  assertEquals(5,query.places(A,3000,null,1,20).total());
  jdbc.update("UPDATE nearby_scan_runs SET expires_at=now()-interval '1 second' WHERE job_id IN (SELECT id FROM nearby_scan_jobs WHERE listing_id=:id)",Map.of("id",A));
  assertEquals(0,query.places(A,3000,null,1,20).total());
  assertEquals(5,query.places(B,3000,null,1,20).total());
 }
 @Test void changingPolicyDoesNotReuseCoverageFromThePreviousEntitlement(){
  scanBoth(true);settings.setLicensePolicyId("different-test-policy");
  var summary=query.summaries(new BatchRequest(List.of(A),3000,Set.of(Group.EDUCATION),1)).getFirst();
  assertEquals("NOT_READY",summary.groups().getFirst().coverageStatus());
  assertEquals(0,summary.groups().getFirst().knownCount());
 }
}
