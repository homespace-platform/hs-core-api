package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

/** SQL belongs to this module, never to an LLM. All user values are bound parameters. */
@Repository
public class NearbyRepository {
 public static final String PUBLIC_LOCATION="""
  FROM listings l JOIN listing_addresses a ON a.listing_id=l.id AND a.active=true
  WHERE l.active=true AND l.status='PUBLISHED' AND (l.expires_at IS NULL OR l.expires_at>now())
   AND a.latitude BETWEEN -90 AND 90 AND a.longitude BETWEEN -180 AND 180
  """;
 public static final String HASH="md5(a.id||':'||a.latitude::text||':'||a.longitude::text)";
 private final NamedParameterJdbcTemplate jdbc; private final TransactionTemplate tx;
 private final ObjectMapper mapper=new ObjectMapper(); private final NearbySettings settings;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private NearbyMetrics metrics;
 public NearbyRepository(NamedParameterJdbcTemplate jdbc,PlatformTransactionManager manager,NearbySettings settings){
  this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.settings=settings;
 }
 public boolean schemaExists(){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('nearby_scan_jobs') IS NOT NULL",Map.of(),Boolean.class));}
 public void validateSchema(){
  for(String table:List.of("nearby_places","nearby_place_sources","nearby_categories","nearby_place_categories","nearby_scan_jobs","nearby_scan_runs","listing_nearby_places"))
   if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(:table) IS NOT NULL",Map.of("table",table),Boolean.class)))
    throw new IllegalStateException("Nearby JPA tables are missing; start Core with JPA_DDL_AUTO=update to create the schema");
 }
 public List<Location> locations(List<String> ids) {
  return jdbc.query("SELECT l.id,a.id address_id,a.latitude,a.longitude,"+HASH+" location_hash "+PUBLIC_LOCATION+" AND l.id IN (:ids)",
   Map.of("ids",ids),this::location);
 }
 private Location location(ResultSet rs,int i)throws SQLException{return new Location(rs.getString("id"),rs.getString("address_id"),
  rs.getDouble("latitude"),rs.getDouble("longitude"),rs.getString("location_hash"));}
 public void reconcile(int radius,int batch) {
  String sql="""
   SELECT l.id PUBLIC_LOCATION
    AND (EXISTS(SELECT 1 FROM listing_nearby_places lp WHERE lp.listing_id=l.id AND lp.listing_location_hash<>HASH)
     OR EXISTS(SELECT 1 FROM nearby_scan_jobs old WHERE old.listing_id=l.id AND old.location_hash<>HASH AND old.status<>'SUPERSEDED')
     OR NOT EXISTS(SELECT 1 FROM nearby_scan_jobs j WHERE j.listing_id=l.id AND j.location_hash=HASH
      AND j.radius_m=:radius AND j.mapping_version=:version
      AND (j.status IN ('PENDING','RUNNING','RETRY_WAIT','FAILED','CANCELLED')
       OR (j.status IN ('SUCCESS','PARTIAL') AND j.next_attempt_at>now()))))
   ORDER BY l.published_at DESC NULLS LAST,l.id LIMIT :batch
   """.replace("PUBLIC_LOCATION",PUBLIC_LOCATION).replace("HASH",HASH);
  for(String id:jdbc.queryForList(sql,Map.of("radius",radius,"batch",batch,"version",NearbyCatalog.VERSION),String.class))ensure(id,radius);
 }
 public void ensure(String listingId,int radius) {
  schedule(listingId,radius,false);
 }
 public void enqueue(String listingId,int radius) {
  if(!schedule(listingId,radius,true))throw new IllegalArgumentException("Listing is not public or has no valid location");
 }
 /** Lock the address before jobs/relations so an old page cannot race a location replacement. */
 private boolean schedule(String listingId,int radius,boolean force) {
  return Boolean.TRUE.equals(tx.execute(status->{
   var current=jdbc.query("SELECT l.id,a.id address_id,a.latitude,a.longitude,"+HASH+" location_hash "+PUBLIC_LOCATION+
    " AND l.id=:listing FOR UPDATE OF a",Map.of("listing",listingId),this::location);
   if(current.isEmpty())return false;
   Location l=current.getFirst();
   Map<String,Object> location=Map.of("listing",listingId,"hash",l.hash());
   // Only this listing's links and scan evidence change. Shared places/sources/categories stay intact.
   int removed=jdbc.update("DELETE FROM listing_nearby_places WHERE listing_id=:listing AND listing_location_hash<>:hash",location);
   jdbc.update("""
    UPDATE nearby_scan_runs r SET coverage_status='STALE',stopped_reason='LISTING_LOCATION_CHANGED'
    FROM nearby_scan_jobs j WHERE r.job_id=j.id AND j.listing_id=:listing AND j.location_hash<>:hash
     AND r.coverage_status<>'STALE'
    """,location);
   int superseded=jdbc.update("""
    UPDATE nearby_scan_jobs SET status='SUPERSEDED',lease_token=NULL,lease_until=NULL,
     last_error_code='LISTING_LOCATION_CHANGED',updated_at=now()
    WHERE listing_id=:listing AND location_hash<>:hash AND status<>'SUPERSEDED'
    """,location);
   jdbc.update("""
    INSERT INTO nearby_scan_jobs(id,listing_id,location_hash,radius_m,mapping_version)
    VALUES(:id,:listing,:hash,:radius,:version)
    ON CONFLICT(listing_id,location_hash,radius_m,mapping_version) DO UPDATE
    SET status='PENDING',attempts=0,next_attempt_at=now(),updated_at=now(),last_error_code=NULL
    WHERE nearby_scan_jobs.status<>'RUNNING' AND (:refresh OR nearby_scan_jobs.status='SUPERSEDED'
     OR (nearby_scan_jobs.status IN ('SUCCESS','PARTIAL') AND nearby_scan_jobs.next_attempt_at<=now()))
    """,Map.of("id",UUID.randomUUID().toString(),"listing",listingId,"hash",l.hash(),"radius",radius,
      "version",NearbyCatalog.VERSION,"refresh",force||removed>0||superseded>0));
   return true;
  }));
 }
 public Job claim() {
  return tx.execute(status->{
   jdbc.update("UPDATE nearby_scan_jobs SET status='FAILED',last_error_code='ATTEMPTS_EXHAUSTED' WHERE status='RUNNING' AND lease_until<now() AND attempts>=:max",
    Map.of("max",settings.getMaxAttempts()));
   var rows=jdbc.query("""
    SELECT id,listing_id,location_hash,radius_m,attempts FROM nearby_scan_jobs
    WHERE ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=now()) OR (status='RUNNING' AND lease_until<now()))
      AND attempts<:max ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1
    """,Map.of("max",settings.getMaxAttempts()),(rs,i)->new Job(rs.getString("id"),rs.getString("listing_id"),rs.getString("location_hash"),
      rs.getInt("radius_m"),rs.getInt("attempts")+1,UUID.randomUUID().toString()));
   if(rows.isEmpty())return null;Job j=rows.getFirst();
   jdbc.update("UPDATE nearby_scan_jobs SET status='RUNNING',attempts=:attempt,lease_token=:token,lease_until=now()+interval '5 minutes',updated_at=now() WHERE id=:id",
    Map.of("attempt",j.attempts(),"token",j.leaseToken(),"id",j.id()));return j;
  });
 }
 public void heartbeat(Job j) {
  if(jdbc.update("UPDATE nearby_scan_jobs SET lease_until=now()+interval '5 minutes' WHERE id=:id AND lease_token=:token AND status='RUNNING' AND lease_until>now()",
    Map.of("id",j.id(),"token",j.leaseToken()))!=1)throw new NearbyProvider.Failure("LOST_LEASE",false);
 }
 public String startRun(Job j,Location l,Group group,Set<String> categoryIds) {
  heartbeat(j);String id=UUID.randomUUID().toString();
  jdbc.update("""
   INSERT INTO nearby_scan_runs(id,job_id,provider,api_version,category_group,center_lat,center_lon,radius_m,requested_category_ids,page_size,expires_at,license_policy_id)
   VALUES(:id,:job,'TOMTOM','V2',:group,:lat,:lon,:radius,CAST(:categories AS jsonb),100,now()+(:hours*interval '1 hour'),:policy)
   """,Map.of("id",id,"job",j.id(),"group",group.name(),"lat",l.latitude(),"lon",l.longitude(),"radius",j.radiusM(),
     "categories",json(categoryIds),"hours",settings.getRetentionHours(),"policy",settings.getLicensePolicyId()));
  return id;
 }
 public void savePage(Job j,Location l,String runId,Page page,Map<String,Set<String>> parents,NearbyCatalog catalog) {
  tx.executeWithoutResult(status->{
   var current=jdbc.query("SELECT l.id,a.id address_id,a.latitude,a.longitude,"+HASH+" location_hash "+PUBLIC_LOCATION+
    " AND l.id=:listing FOR SHARE OF a",Map.of("listing",j.listingId()),this::location);
   if(current.stream().noneMatch(v->v.hash().equals(j.locationHash())))throw new NearbyProvider.Failure("SUPERSEDED",false);
   List<String> locked=jdbc.queryForList("SELECT id FROM nearby_scan_jobs WHERE id=:id AND lease_token=:token AND status='RUNNING' AND lease_until>now() FOR UPDATE",
    Map.of("id",j.id(),"token",j.leaseToken()),String.class);
   if(locked.isEmpty())throw new NearbyProvider.Failure("SUPERSEDED",false);
   Set<String> seen=new HashSet<>();
   for(Poi p:page.places()) {
    if(!seen.add(p.externalId())||distance(l.latitude(),l.longitude(),p.latitude(),p.longitude())>j.radiusM()+1)continue;
    jdbc.getJdbcTemplate().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"TOMTOM:V2:"+p.externalId());
    var old=jdbc.queryForList("SELECT place_id FROM nearby_place_sources WHERE provider='TOMTOM' AND api_version='V2' AND external_id=:external",
      Map.of("external",p.externalId()),String.class);
    String place=old.isEmpty()?UUID.randomUUID().toString():old.getFirst();
    java.math.BigDecimal lat=java.math.BigDecimal.valueOf(p.latitude()).setScale(7,java.math.RoundingMode.HALF_UP);
    java.math.BigDecimal lon=java.math.BigDecimal.valueOf(p.longitude()).setScale(7,java.math.RoundingMode.HALF_UP);
    MapSqlParameterSource values=new MapSqlParameterSource().addValue("place",place).addValue("name",p.name())
     .addValue("normalized",normalize(p.name())).addValue("lat",lat).addValue("lon",lon)
     .addValue("address",p.address()).addValue("external",p.externalId()).addValue("info",p.info())
     .addValue("categories",json(p.categoryIds())).addValue("classifications",p.classifications())
     .addValue("labels",p.categoryLabels()).addValue("sourceAddress",p.sourceAddress()).addValue("policy",settings.getLicensePolicyId())
     .addValue("hours",settings.getRetentionHours()).addValue("source",UUID.randomUUID().toString());
    try {values.addValue("classifications",json(Map.of("providerClassifications",p.classifications()==null?List.of():mapper.readTree(p.classifications()),
      "verifiedCategoryParents",catalog.evidence(p.categoryIds(),parents))));}
    catch(Exception malformed){throw new IllegalArgumentException("Invalid category metadata");}
    jdbc.update("""
     INSERT INTO nearby_places(id,name,normalized_name,latitude,longitude,address_text)
     VALUES(:place,:name,:normalized,:lat,:lon,:address)
     ON CONFLICT(id) DO UPDATE SET name=:name,normalized_name=:normalized,address_text=:address,latitude=:lat,longitude=:lon,
      position_revision=nearby_places.position_revision+CASE WHEN nearby_places.latitude<>:lat OR nearby_places.longitude<>:lon THEN 1 ELSE 0 END,updated_at=now()
     """,values);
    jdbc.update("""
     INSERT INTO nearby_place_sources(id,place_id,provider,api_version,external_id,external_info,category_ids,classifications,category_labels,source_address,
      license_policy_id,attribution,content_expires_at)
     VALUES(:source,:place,'TOMTOM','V2',:external,:info,CAST(:categories AS jsonb),CAST(:classifications AS jsonb),CAST(:labels AS jsonb),CAST(:sourceAddress AS jsonb),
      :policy,'© TomTom',now()+(:hours*interval '1 hour'))
     ON CONFLICT(provider,api_version,external_id) DO UPDATE SET external_info=:info,category_ids=CAST(:categories AS jsonb),
      classifications=CAST(:classifications AS jsonb),category_labels=CAST(:labels AS jsonb),source_address=CAST(:sourceAddress AS jsonb),
      license_policy_id=:policy,last_seen_at=now(),content_expires_at=now()+(:hours*interval '1 hour')
     """,values);
    jdbc.update("DELETE FROM nearby_place_categories WHERE place_id=:place",Map.of("place",place));
    for(Group g:catalog.classify(p.categoryIds(),parents))
     jdbc.update("INSERT INTO nearby_place_categories(place_id,category_code,mapping_version) VALUES(:place,:group,:version) ON CONFLICT DO NOTHING",
      Map.of("place",place,"group",g.name(),"version",NearbyCatalog.VERSION));
    values.addValue("listing",j.listingId()).addValue("hash",l.hash()).addValue("distance",distance(l.latitude(),l.longitude(),lat.doubleValue(),lon.doubleValue())).addValue("run",runId);
    jdbc.update("""
     INSERT INTO listing_nearby_places(listing_id,place_id,straight_distance_m,listing_location_hash,place_position_revision,evidence_run_id)
     SELECT :listing,id,:distance,:hash,position_revision,:run FROM nearby_places WHERE id=:place
     ON CONFLICT(listing_id,place_id) DO UPDATE SET straight_distance_m=:distance,listing_location_hash=:hash,
      place_position_revision=excluded.place_position_revision,last_calculated_at=now(),evidence_run_id=:run
     """,values);
   }
   jdbc.update("""
    UPDATE nearby_scan_runs SET pages_fetched=pages_fetched+1,next_offset=:next,returned_count=returned_count+:count,
     unique_count=(SELECT count(*) FROM listing_nearby_places WHERE evidence_run_id=:id),
     total_results_reported=:total,page_summaries=page_summaries||CAST(:summary AS jsonb) WHERE id=:id
    """,Map.of("id",runId,"next",page.offset()+page.numResults(),"count",page.numResults(),"total",page.totalResults(),
      "summary",json(List.of(Map.of("offset",page.offset(),"numResults",page.numResults(),"totalResults",page.totalResults(),"malformed",page.malformedCount())))));
  });
 }
 public void finishRun(String run,String state,String reason) {
  jdbc.update("UPDATE nearby_scan_runs SET coverage_status=:state,stopped_reason=:reason,finished_at=now() WHERE id=:id",
   new MapSqlParameterSource().addValue("state",state).addValue("reason",reason).addValue("id",run));
 }
 public void finishJob(Job job,String state,String error,long delaySeconds) {
  int updated=jdbc.update("""
   UPDATE nearby_scan_jobs SET status=:state,last_error_code=:error,next_attempt_at=now()+(:delay*interval '1 second'),
   attempts=CASE WHEN :quota THEN greatest(0,attempts-1) ELSE attempts END,
   lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:id AND lease_token=:token AND status='RUNNING'
   """,new MapSqlParameterSource().addValue("state",state).addValue("error",error).addValue("delay",delaySeconds)
     .addValue("id",job.id()).addValue("token",job.leaseToken()).addValue("quota",error!=null&&(error.startsWith("QUOTA_")||error.equals("PROVIDER_PAUSED"))));
  if(updated==1&&metrics!=null)metrics.job(state);
 }
 public List<Map<String,Object>> jobs(int limit){return jdbc.queryForList("""
  SELECT id,listing_id,radius_m,status,attempts,next_attempt_at,last_error_code,updated_at FROM nearby_scan_jobs ORDER BY updated_at DESC LIMIT :limit
  """,Map.of("limit",limit));}
 public boolean cancel(String id){return jdbc.update("UPDATE nearby_scan_jobs SET status='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:id AND status IN ('PENDING','RUNNING','RETRY_WAIT','PARTIAL')",Map.of("id",id))==1;}
 public void purgeExpired() {
  tx.executeWithoutResult(status->{
   jdbc.update("DELETE FROM nearby_place_sources WHERE content_expires_at<=now()",Map.of());
   jdbc.update("DELETE FROM nearby_places p WHERE NOT EXISTS(SELECT 1 FROM nearby_place_sources s WHERE s.place_id=p.id)",Map.of());
  });
 }
 public NamedParameterJdbcTemplate jdbc(){return jdbc;}
 public static String normalize(String value){return java.text.Normalizer.normalize(value.toLowerCase(Locale.ROOT).replace('đ','d'),
   java.text.Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("\\s+"," ").trim();}
 private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException("Invalid nearby metadata");}}
}
