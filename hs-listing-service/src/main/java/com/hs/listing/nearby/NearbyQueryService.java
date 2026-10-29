package com.hs.listing.nearby;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.*;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

/** Read-only projections. No provider dependency means chat can never cause a POI fetch. */
@Service
@Transactional(readOnly=true)
public class NearbyQueryService {
 private final NearbyRepository repo;private final NearbySettings settings;
 private final NearbyCatalog catalog=new NearbyCatalog();
 // Rebuild display addresses for already cached rows too; no provider fetch or SQL repair is needed.
 private static final String SOURCE_ADDRESS="""
  (SELECT s.source_address FROM nearby_place_sources s WHERE s.place_id=p.id AND s.provider='TOMTOM'
   AND s.api_version='V2' AND (:retentionHours=0 OR s.content_expires_at>now()) AND s.license_policy_id=:policy
   ORDER BY s.last_seen_at DESC,s.id LIMIT 1) AS source_address
  """;
 public NearbyQueryService(NearbyRepository repo,NearbySettings settings){this.repo=repo;this.settings=settings;}
 public boolean enabled(){return settings.readable();}
 public void requireEnabled(){if(!enabled())throw new IllegalArgumentException("Nearby query is disabled or not licensed");}
 private static final String CURRENT_PLACES="""
  FROM listing_nearby_places lp JOIN nearby_places p ON p.id=lp.place_id AND p.active=true
  JOIN listings l ON l.id=lp.listing_id JOIN listing_addresses a ON a.listing_id=l.id AND a.active=true
  WHERE l.active=true AND l.status='PUBLISHED' AND (l.expires_at IS NULL OR l.expires_at>now())
   AND lp.listing_location_hash=HASH AND lp.place_position_revision=p.position_revision
   AND EXISTS(SELECT 1 FROM nearby_place_categories scoped WHERE scoped.place_id=p.id AND scoped.category_code IN (:scopeGroups))
   AND EXISTS(SELECT 1 FROM nearby_scan_runs evidence JOIN nearby_scan_jobs job ON job.id=evidence.job_id
    WHERE evidence.id=lp.evidence_run_id AND (:retentionHours=0 OR evidence.expires_at>now()) AND evidence.license_policy_id=:policy AND job.mapping_version=:version)
   AND EXISTS(SELECT 1 FROM nearby_place_sources s WHERE s.place_id=p.id AND (:retentionHours=0 OR s.content_expires_at>now())
     AND s.license_policy_id=:policy)
  """.replace("HASH",NearbyRepository.HASH);
 public List<Summary> summaries(BatchRequest request) {
  List<String> ids=request.listingIds();
  if(ids==null||ids.isEmpty()||ids.size()>20||ids.stream().anyMatch(Objects::isNull)||new HashSet<>(ids).size()!=ids.size())throw new IllegalArgumentException("Supply 1 to 20 unique listing IDs");
  ids.forEach(v->UUID.fromString(v));int radius=settings.radius(request.radiusMeters());
  int nearest=request.nearestPerGroup()==null?3:request.nearestPerGroup();
  if(nearest<1||nearest>5)throw new IllegalArgumentException("nearestPerGroup must be between 1 and 5");
  Set<Group> scannedGroups=catalog.requestedGroups(settings.categoryIds());
  Set<Group> groups=request.groups()==null||request.groups().isEmpty()?scannedGroups:request.groups();
  if(groups.stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("Invalid nearby group");
  if(!enabled())return ids.stream().map(id->new Summary(id,false,radius,List.of(),"Tính năng tiện ích xung quanh chưa được bật.")).toList();
  List<Location> locations=repo.locations(ids);
  Set<String> publicIds=new HashSet<>(locations.stream().map(Location::listingId).toList());
  if(publicIds.size()!=ids.size())throw new IllegalArgumentException("Listing is not public or has no valid location");
  Target target=null;
  if(request.targetPlaceId()!=null) {
   UUID.fromString(request.targetPlaceId());
   var targets=repo.jdbc().query("SELECT p.id,p.name,p.latitude,p.longitude,"+SOURCE_ADDRESS+" FROM nearby_places p WHERE p.id=:id AND p.active=true AND EXISTS(SELECT 1 FROM nearby_place_sources s WHERE s.place_id=p.id AND (:retentionHours=0 OR s.content_expires_at>now()) AND s.license_policy_id=:policy)",
    Map.of("id",request.targetPlaceId(),"policy",settings.getLicensePolicyId(),"retentionHours",settings.getRetentionHours()),(rs,i)->target(rs));
   if(targets.isEmpty())throw new IllegalArgumentException("Target place is unavailable");
   target=targets.getFirst();
  }
  MapSqlParameterSource params=params(radius).addValue("ids",ids).addValue("groups",groups.stream().map(Enum::name).toList()).addValue("limit",nearest);
  String sql="""
   WITH candidates AS (
    SELECT lp.listing_id,p.id,p.name,p.latitude,p.longitude,SOURCE_ADDRESS,lp.straight_distance_m,c.category_code,
     row_number() OVER(PARTITION BY lp.listing_id,c.category_code ORDER BY lp.straight_distance_m,p.id) n,
     count(*) OVER(PARTITION BY lp.listing_id,c.category_code) known_count
    CURRENT AND lp.listing_id IN (:ids) AND lp.straight_distance_m<=:radius
     AND EXISTS(SELECT 1 FROM nearby_place_categories cc WHERE cc.place_id=p.id AND cc.category_code IN (:groups))
   ) SELECT * FROM candidates WHERE n<=:limit ORDER BY listing_id,category_code,n
   """;
  // Category join happens before the WHERE of the shared public projection.
  String from=CURRENT_PLACES.replace("WHERE l.active", "JOIN nearby_place_categories c ON c.place_id=p.id AND c.category_code IN (:groups) WHERE l.active");
  var results=repo.jdbc().query(sql.replace("CURRENT",from).replace("SOURCE_ADDRESS",SOURCE_ADDRESS),params,(rs,i)->new Evidence(rs.getString("listing_id"),Group.valueOf(rs.getString("category_code")),
    rs.getLong("known_count"),place(rs,List.of(rs.getString("category_code")))));
  List<Summary> summaries=new ArrayList<>();
  Map<String,Map<String,Object>> coverages=coverage(ids,groups,radius);
  for(String id:ids) {
   List<GroupSummary> output=new ArrayList<>();
   for(Group group:Group.values())if(groups.contains(group)){
    var items=results.stream().filter(e->e.listing.equals(id)&&e.group==group).toList();
    Map<String,Object> coverage=coverages.getOrDefault(id+":"+group.name(),Map.of("state",scannedGroups.contains(group)?"NOT_READY":"NOT_REQUESTED"));
    output.add(new GroupSummary(group,items.isEmpty()?0:items.getFirst().count,items.stream().map(Evidence::place).toList(),
      (String)coverage.get("state"),(Instant)coverage.get("at"),(Integer)coverage.get("radius")));
   }
   TargetDistance targetDistance=null;
   if(target!=null) {
    Location l=locations.stream().filter(v->v.listingId().equals(id)).findFirst().orElseThrow();
    double meters=distance(l.latitude(),l.longitude(),target.latitude(),target.longitude());
    targetDistance=new TargetDistance(target.id(),target.name(),meters,meters<=radius,"STRAIGHT_LINE");
   }
   summaries.add(new Summary(id,true,radius,output,"Khoảng cách đường chim bay. Mỗi lượt lấy tối đa "+settings.getResultLimit()+" địa điểm từ một phản hồi TomTom; nhóm có 0 kết quả chưa chứng minh không có tiện ích trong khu vực.",targetDistance));
  }
  return summaries;
 }
 private record Evidence(String listing,Group group,long count,Place place){}
 private Map<String,Map<String,Object>> coverage(List<String> ids,Set<Group> groups,int radius) {
  Map<String,Map<String,Object>> result=new HashMap<>();
  repo.jdbc().query("""
   WITH ranked AS (SELECT j.listing_id,requested.category_group,r.coverage_status,r.finished_at,r.radius_m,
    row_number() OVER(PARTITION BY j.listing_id,requested.category_group ORDER BY r.started_at DESC,r.id DESC) n
   FROM nearby_scan_runs r JOIN nearby_scan_jobs j ON j.id=r.job_id
    CROSS JOIN LATERAL jsonb_array_elements_text(r.requested_groups) requested(category_group)
    JOIN listing_addresses a ON a.listing_id=j.listing_id AND a.active=true
   WHERE j.listing_id IN (:ids) AND j.location_hash=HASH AND requested.category_group IN (:groups) AND (:retentionHours=0 OR r.expires_at>now())
    AND j.mapping_version=:version AND r.license_policy_id=:policy
   ) SELECT * FROM ranked WHERE n=1
   """.replace("HASH",NearbyRepository.HASH),Map.of("ids",ids,"groups",groups.stream().map(Enum::name).toList(),"radius",radius,"version",settings.scanVersion(),"policy",settings.getLicensePolicyId(),"retentionHours",settings.getRetentionHours()),
    (org.springframework.jdbc.core.RowCallbackHandler)rs->{
     Map<String,Object> m=new HashMap<>();String state=rs.getString("coverage_status");int covered=rs.getInt("radius_m");
     m.put("state",covered<radius?"PARTIAL":state);m.put("radius",covered);
     Timestamp at=rs.getTimestamp("finished_at");if(at!=null)m.put("at",at.toInstant());
     result.put(rs.getString("listing_id")+":"+rs.getString("category_group"),m);
    });
  // A shared POI can move when another listing refreshes it. Never report complete
  // coverage for a listing whose stored distances still use the previous position.
  Set<String> staleListings=new HashSet<>(repo.jdbc().queryForList("""
   SELECT DISTINCT lp.listing_id FROM listing_nearby_places lp
    JOIN nearby_places p ON p.id=lp.place_id
    JOIN listing_addresses a ON a.listing_id=lp.listing_id AND a.active=true
   WHERE lp.listing_id IN (:ids) AND lp.listing_location_hash=HASH
    AND lp.place_position_revision<>p.position_revision
   """.replace("HASH",NearbyRepository.HASH),Map.of("ids",ids),String.class));
  for(String id:staleListings)for(Group group:groups){
   Map<String,Object> metadata=result.get(id+":"+group.name());
   if(metadata!=null)metadata.put("state","STALE");
  }
  return result;
 }
 public PlacePage places(String listingId,Integer radius,Set<Group> groups,int page,Integer size) {
  int pageSize=size==null?settings.getPublicPageSize():size;
  if(page<1||page>1000||pageSize<1||pageSize>50)throw new IllegalArgumentException("Invalid page or size");
  pageSize=Math.min(pageSize,settings.getPublicPageSize());
  Summary summary=summaries(new BatchRequest(List.of(listingId),radius,groups,3)).getFirst();
  if(!summary.enabled())return new PlacePage(false,List.of(),0,page,pageSize,summary);
  MapSqlParameterSource params=params(summary.radiusRequestedMeters()).addValue("id",listingId).addValue("limit",pageSize).addValue("offset",(page-1)*pageSize);
  String groupFilter="";
  if(groups!=null&&!groups.isEmpty()){
   groupFilter=" AND EXISTS(SELECT 1 FROM nearby_place_categories c WHERE c.place_id=p.id AND c.category_code IN (:groups))";
   params.addValue("groups",groups.stream().map(Enum::name).toList());
  }
  String from=CURRENT_PLACES+" AND lp.listing_id=:id AND lp.straight_distance_m<=:radius"+groupFilter;
  long total=repo.jdbc().queryForObject("SELECT count(*) "+from,params,Long.class);
  var items=repo.jdbc().query("SELECT p.*,lp.straight_distance_m,"+SOURCE_ADDRESS+" "+from+" ORDER BY lp.straight_distance_m,p.id LIMIT :limit OFFSET :offset",params,
   (rs,i)->place(rs,List.of()));
  // One bounded category query for the page, not a request for every marker.
  if(!items.isEmpty()) {
   Map<String,List<String>> categories=new HashMap<>();
   repo.jdbc().query("SELECT place_id,category_code FROM nearby_place_categories WHERE place_id IN (:ids)",Map.of("ids",items.stream().map(Place::id).toList()),
     (org.springframework.jdbc.core.RowCallbackHandler)rs->categories.computeIfAbsent(rs.getString(1),k->new ArrayList<>()).add(rs.getString(2)));
   items=items.stream().map(p->new Place(p.id(),p.name(),p.latitude(),p.longitude(),p.address(),p.distanceMeters(),p.distanceType(),categories.getOrDefault(p.id(),List.of()),p.attribution())).toList();
  }
  return new PlacePage(true,items,total,page,pageSize,summary);
 }
 public List<Target> targets(String keyword) {
  requireEnabled();if(keyword==null||keyword.strip().length()<2||keyword.length()>160)throw new IllegalArgumentException("Place query must be 2 to 160 characters");
  String term=NearbyRepository.normalize(keyword).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
  return repo.jdbc().query("""
   SELECT p.id,p.name,p.latitude,p.longitude,SOURCE_ADDRESS FROM nearby_places p WHERE p.active=true
    AND p.normalized_name LIKE :term ESCAPE '\\'
    AND EXISTS(SELECT 1 FROM nearby_place_sources s WHERE s.place_id=p.id AND (:retentionHours=0 OR s.content_expires_at>now()) AND s.license_policy_id=:policy)
   ORDER BY CASE WHEN p.normalized_name=:exact THEN 0 ELSE 1 END,p.name,p.id LIMIT 10
   """.replace("SOURCE_ADDRESS",SOURCE_ADDRESS),Map.of("term","%"+term+"%","exact",NearbyRepository.normalize(keyword),"policy",settings.getLicensePolicyId(),"retentionHours",settings.getRetentionHours()),
    (rs,i)->target(rs));
 }
 /** Embedded in the existing typed listing compiler BEFORE count/order/LIMIT. */
 public String searchPredicate(String placeId,Group group,Integer radius,MapSqlParameterSource params) {
  if(placeId==null&&group==null)throw new IllegalArgumentException("A place or group is required");
  return " AND ("+(placeId!=null?targetPredicate(requireTarget(placeId),radius,params,""):groupPredicate(group,null,radius,params,""))+")";
 }
 public record TargetPoint(String id,String name,double latitude,double longitude){}
 /** Validated once per search: active, licensed and retained. Its coordinates are then bound as parameters. */
 public TargetPoint requireTarget(String placeId) {
  requireEnabled();
  try{UUID.fromString(placeId);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid target place ID");}
  var targets=repo.jdbc().query("SELECT p.id,p.name,p.latitude,p.longitude FROM nearby_places p WHERE p.id=:id AND p.active=true AND EXISTS(SELECT 1 FROM nearby_place_sources s WHERE s.place_id=p.id AND (:retentionHours=0 OR s.content_expires_at>now()) AND s.license_policy_id=:policy)",
   Map.of("id",placeId,"policy",settings.getLicensePolicyId(),"retentionHours",settings.getRetentionHours()),
   (rs,i)->new TargetPoint(rs.getString("id"),rs.getString("name"),rs.getDouble("latitude"),rs.getDouble("longitude")));
  if(targets.isEmpty())throw new IllegalArgumentException("Target place is unavailable");
  return targets.getFirst();
 }
 /** Same Haversine radius as {@link NearbyTypes#distance}; NULL (unknown) when the listing has no stored coordinates. */
 public static String distanceSql(String lat,String lon) {
  return ("(SELECT 2*6371008.8*asin(least(1,sqrt(power(sin(radians(CAST(da.latitude AS double precision)-:LAT)/2),2)"
   +"+cos(radians(:LAT))*cos(radians(CAST(da.latitude AS double precision)))*power(sin(radians(CAST(da.longitude AS double precision)-:LON)/2),2))))"
   +" FROM listing_addresses da WHERE da.listing_id=l.id AND da.active=true AND da.latitude IS NOT NULL AND da.longitude IS NOT NULL)")
   .replace("LAT",lat).replace("LON",lon);
 }
 /** Distance to the named target from stored listing coordinates. It needs no listing-to-POI link or scan. */
 public String targetPredicate(TargetPoint target,Integer radius,MapSqlParameterSource params,String suffix) {
  int meters=settings.radius(radius);
  params.addValue("targetLat"+suffix,target.latitude()).addValue("targetLon"+suffix,target.longitude()).addValue("targetRadius"+suffix,meters);
  return distanceSql("targetLat"+suffix,"targetLon"+suffix)+"<=:targetRadius"+suffix;
 }
 public List<NearbyKinds.Kind> kinds(){return NearbyKinds.available(settings.categoryIds());}
 /**
  * TRUE with a current cached link, FALSE only when complete provider coverage for this location, scope and radius
  * proves absence, otherwise NULL: an unscanned or limited listing is unknown, not "not near".
  */
 public String groupPredicate(Group group,String kindCode,Integer radius,MapSqlParameterSource params,String suffix) {
  requireEnabled();int meters=settings.radius(radius);
  if(group==null&&kindCode==null)throw new IllegalArgumentException("A nearby group or kind is required");
  NearbyKinds.Kind kind=null;
  if(kindCode!=null){
   kind=kinds().stream().filter(k->k.code().equals(kindCode)).findFirst()
    .orElseThrow(()->new IllegalArgumentException("Unsupported nearby kind: "+kindCode));
   if(group!=null&&group!=kind.group())throw new IllegalArgumentException("Nearby kind does not belong to the group");
   group=kind.group();
  }
  if(!catalog.requestedGroups(settings.categoryIds()).contains(group))throw new IllegalArgumentException("Nearby group is not collected: "+group);
  params.addValue("nearPolicy",settings.getLicensePolicyId()).addValue("nearVersion",settings.scanVersion())
   .addValue("nearRetentionHours",settings.getRetentionHours()).addValue("nearRadius"+suffix,meters).addValue("nearGroup"+suffix,group.name());
  String kindMatch="";
  if(kind!=null){
   params.addValue("nearKind"+suffix,kind.categoryId());
   kindMatch=" AND EXISTS(SELECT 1 FROM nearby_place_sources ks WHERE ks.place_id=np.id AND ks.license_policy_id=:nearPolicy"
    +" AND (:nearRetentionHours=0 OR ks.content_expires_at>now()) AND jsonb_exists(ks.classifications->'verifiedCategoryParents',:nearKind"+suffix+"))";
  }
  String link="""
   EXISTS(SELECT 1 FROM listing_nearby_places nl JOIN nearby_places np ON np.id=nl.place_id AND np.active=true
    JOIN listing_addresses na ON na.listing_id=nl.listing_id AND na.active=true
    WHERE nl.listing_id=l.id AND nl.straight_distance_m<=:nearRadiusS
     AND nl.listing_location_hash=md5(na.id||':'||na.latitude::text||':'||na.longitude::text)
     AND nl.place_position_revision=np.position_revision
     AND EXISTS(SELECT 1 FROM nearby_scan_runs evidence JOIN nearby_scan_jobs job ON job.id=evidence.job_id
      WHERE evidence.id=nl.evidence_run_id AND (:nearRetentionHours=0 OR evidence.expires_at>now()) AND evidence.license_policy_id=:nearPolicy AND job.mapping_version=:nearVersion)
     AND EXISTS(SELECT 1 FROM nearby_place_sources ns WHERE ns.place_id=np.id AND (:nearRetentionHours=0 OR ns.content_expires_at>now()) AND ns.license_policy_id=:nearPolicy)
     AND EXISTS(SELECT 1 FROM nearby_place_categories nc WHERE nc.place_id=np.id AND nc.category_code=:nearGroupS)KIND)
   """.replace("KIND",kindMatch);
  String complete="""
   EXISTS(SELECT 1 FROM nearby_scan_runs cr JOIN nearby_scan_jobs cj ON cj.id=cr.job_id
    JOIN listing_addresses ca ON ca.listing_id=cj.listing_id AND ca.active=true
    WHERE cj.listing_id=l.id AND cj.location_hash=md5(ca.id||':'||ca.latitude::text||':'||ca.longitude::text)
     AND cj.mapping_version=:nearVersion AND cr.license_policy_id=:nearPolicy AND (:nearRetentionHours=0 OR cr.expires_at>now())
     AND cr.coverage_status='COMPLETE_PROVIDER_RESPONSE' AND cr.radius_m>=:nearRadiusS AND jsonb_exists(cr.requested_groups,:nearGroupS))
   AND NOT EXISTS(SELECT 1 FROM listing_nearby_places sl JOIN nearby_places sp ON sp.id=sl.place_id
    WHERE sl.listing_id=l.id AND sl.place_position_revision<>sp.position_revision)
   """;
  return ("(CASE WHEN "+link.strip()+" THEN true WHEN "+complete.strip()+" THEN false ELSE NULL END)").replace(":nearRadiusS",":nearRadius"+suffix).replace(":nearGroupS",":nearGroup"+suffix);
 }
 private MapSqlParameterSource params(int radius){return new MapSqlParameterSource().addValue("policy",settings.getLicensePolicyId()).addValue("radius",radius)
  .addValue("version",settings.scanVersion()).addValue("retentionHours",settings.getRetentionHours())
  .addValue("scopeGroups",catalog.requestedGroups(settings.categoryIds()).stream().map(Enum::name).toList());}
 private static Place place(ResultSet rs,List<String> groups)throws SQLException {
  return new Place(rs.getString("id"),rs.getString("name"),rs.getDouble("latitude"),rs.getDouble("longitude"),NearbyAddress.fromStored(rs.getString("source_address")),
   rs.getDouble("straight_distance_m"),"STRAIGHT_LINE",groups,"© TomTom");
 }
 private static Target target(ResultSet rs)throws SQLException {
  return new Target(rs.getString("id"),rs.getString("name"),NearbyAddress.fromStored(rs.getString("source_address")),rs.getDouble("latitude"),rs.getDouble("longitude"));
 }
}
