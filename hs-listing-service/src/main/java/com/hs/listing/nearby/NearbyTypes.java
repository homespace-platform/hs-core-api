package com.hs.listing.nearby;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NearbyTypes {
 private NearbyTypes() {}
 public enum Group { EDUCATION, HEALTHCARE, SHOPPING, FOOD_DRINK, FINANCE, TRANSPORT, RECREATION, SPORTS }
 public record Location(String listingId, String addressId, double latitude, double longitude, String hash) {}
 public record Job(String id, String listingId, String locationHash, int radiusM, int attempts, String leaseToken) {}
 public record Poi(String externalId, String name, double latitude, double longitude, String address,
                   String info, Set<String> categoryIds, String classifications, String categoryLabels, String sourceAddress) {}
 public record Page(int offset, int numResults, int totalResults, List<Poi> places, int malformedCount) {}
 public record Place(String id, String name, double latitude, double longitude, String address,
                     double distanceMeters, String distanceType, List<String> groups, String attribution) {}
 public record GroupSummary(Group code, long knownCount, List<Place> nearest, String coverageStatus, Instant dataAsOf, Integer radiusCoveredMeters) {}
 public record TargetDistance(String placeId,String name,double distanceMeters,boolean withinRadius,String distanceType) {}
 public record Summary(String listingId, boolean enabled, int radiusRequestedMeters, List<GroupSummary> groups,
                       String limitation,TargetDistance target) {
  public Summary(String id,boolean enabled,int radius,List<GroupSummary> groups,String limitation){this(id,enabled,radius,groups,limitation,null);}
 }
 public record BatchRequest(List<String> listingIds, Integer radiusMeters, Set<Group> groups, Integer nearestPerGroup,String targetPlaceId) {
  public BatchRequest(List<String> ids,Integer radius,Set<Group> groups,Integer nearest){this(ids,radius,groups,nearest,null);}
 }
 public record PlacePage(boolean enabled, List<Place> items, long total, int page, int size, Summary coverage) {}
 public record Target(String id, String name, String address, double latitude, double longitude) {}
 public record ScanChanged(String listingId) {}
 public static double distance(double lat1,double lon1,double lat2,double lon2) {
  double dLat=Math.toRadians(lat2-lat1),dLon=Math.toRadians(lon2-lon1);
  double a=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
  return 6371008.8*2*Math.atan2(Math.sqrt(a),Math.sqrt(Math.max(0,1-a)));
 }
 public static boolean valid(double lat, double lon) {return Double.isFinite(lat)&&Double.isFinite(lon)&&Math.abs(lat)<=90&&Math.abs(lon)<=180;}
}
