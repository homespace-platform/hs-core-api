package com.hs.listing.nearby;

import java.util.*;
import java.util.regex.Pattern;
import static com.hs.listing.nearby.NearbyTypes.*;

/** Conservative response-local entity matching. Never merges/deletes the shared POI library. */
final class NearbyPoiDeduplicator {
 private NearbyPoiDeduplicator() {}
 private static final Pattern NUMBERS=Pattern.compile("\\d+");
 private static final Pattern BRANCH=Pattern.compile("\\b(?:chi nhanh|co so|campus|cn|cs|phong giao dich|pgd)\\b.*");
 private static final Pattern INSTITUTION=Pattern.compile("^(?:dai hoc|cao dang|tieu hoc|trung hoc|benh vien)\\b|\\b(?:university|college|hospital)\\b");
 private record Candidate(Poi poi,String name,Set<String> kinds,double distance) {}

 static List<Poi> select(Location listing,List<Poi> places,int radiusM,NearbySettings settings,
                         Map<String,Set<String>> parents) {
  List<Candidate> sorted=new ArrayList<>();
  for(Poi poi:places) {
   if(!valid(poi.latitude(),poi.longitude()))continue;
   double distance=distance(listing.latitude(),listing.longitude(),poi.latitude(),poi.longitude());
   if(distance>radiusM+1)continue;
   sorted.add(new Candidate(poi,normalizedName(poi.name()),kinds(poi.categoryIds(),parents,settings.categoryIds()),distance));
  }
  // Compare real listing-to-POI coordinates, never provider ordering or the address string.
  sorted.sort(Comparator.comparingDouble(Candidate::distance).thenComparing(c->c.poi().externalId())
    .thenComparing(c->c.poi().name()).thenComparingDouble(c->c.poi().latitude()).thenComparingDouble(c->c.poi().longitude()));
  List<Candidate> kept=new ArrayList<>();Set<String> externalIds=new HashSet<>();
  for(Candidate candidate:sorted) {
   if(!externalIds.add(candidate.poi().externalId()))continue;
   if(kept.stream().noneMatch(other->samePlace(candidate,other,settings)))kept.add(candidate);
  }
  return kept.stream().map(Candidate::poi).toList();
 }

 private static boolean samePlace(Candidate a,Candidate b,NearbySettings settings) {
  if(a.name().isBlank()||b.name().isBlank()||Collections.disjoint(a.kinds(),b.kinds()))return false;
  if(!numbers(a.name()).equals(numbers(b.name())))return false;
  if(!branch(a.name()).equals(branch(b.name())))return false;
  double separation=distance(a.poi().latitude(),a.poi().longitude(),b.poi().latitude(),b.poi().longitude());
  if(separation>settings.getDedupInstitutionRadiusM())return false;
  if(!similar(a.name(),b.name(),settings.getDedupMinimumNameSimilarity()))return false;
  if(separation<=settings.getDedupSamePlaceRadiusM()) {
   if(distinctiveName(a.name())&&distinctiveName(b.name()))return true;
   // A short brand can have two real shops across the road. Require an address match
   // as additional evidence, rather than deleting one based on the brand alone.
   String address=NearbyRepository.normalize(a.poi().address());
   return !address.isBlank()&&address.equals(NearbyRepository.normalize(b.poi().address()));
  }
  // A chain's brand alone is insufficient to merge shops on different streets. The larger
  // range is limited to long, named schools/hospitals with a compatible provider category.
  return largeInstitution(a)&&largeInstitution(b);
 }

 private static boolean largeInstitution(Candidate candidate) {
  return !Collections.disjoint(candidate.kinds(),Set.of("7372","7377","7321"))
    && distinctiveName(candidate.name())
    && INSTITUTION.matcher(candidate.name()).find();
 }

 private static boolean distinctiveName(String name) {return name.length()>=20&&name.split(" ").length>=4;}

 static String normalizedName(String name) {
  String value=NearbyRepository.normalize(name).replace("+"," plus ").replaceAll("[^a-z0-9 ]"," ").replaceAll("\\s+"," ").trim();
  value=value.replaceAll("\\b(?:tp hcm|tphcm|tp ho chi minh|hcm)\\b","thanh pho ho chi minh")
    .replaceAll("\\btp\\b","thanh pho").replaceAll("\\bdh\\b","dai hoc").replaceAll("\\bcd\\b","cao dang");
  return value.replaceFirst("^truong (?=(?:dai hoc|cao dang|tieu hoc|trung hoc)\\b)","");
 }

 private static List<String> numbers(String value) {return NUMBERS.matcher(value).results().map(m->m.group()).toList();}
 private static String branch(String value) {var match=BRANCH.matcher(value);return match.find()?match.group():"";}

 private static Set<String> kinds(Set<String> ids,Map<String,Set<String>> parents,Set<String> approved) {
  Set<String> seen=new HashSet<>(),result=new HashSet<>();Deque<String> pending=new ArrayDeque<>(ids);
  while(!pending.isEmpty()&&seen.size()<1000) {
   String id=pending.removeFirst();if(!seen.add(id))continue;
   if(approved.contains(id))result.add(id);
   pending.addAll(parents.getOrDefault(id,Set.of()));
  }
  return result;
 }

 /** Banded edit distance: tiny spelling differences only; no token-subset/brand matching. */
 private static boolean similar(String a,String b,double minimum) {
  if(a.equals(b))return true;
  int length=Math.max(a.length(),b.length()),limit=(int)Math.floor((1-minimum)*length+1e-9);
  if(limit==0||Math.abs(a.length()-b.length())>limit)return false;
  int[] previous=new int[b.length()+1],current=new int[b.length()+1];
  for(int j=0;j<previous.length;j++)previous[j]=j;
  for(int i=1;i<=a.length();i++) {
   Arrays.fill(current,limit+1);current[0]=i;int best=limit+1;
   for(int j=Math.max(1,i-limit);j<=Math.min(b.length(),i+limit);j++) {
    current[j]=Math.min(Math.min(previous[j]+1,current[j-1]+1),previous[j-1]+(a.charAt(i-1)==b.charAt(j-1)?0:1));
    best=Math.min(best,current[j]);
   }
   if(best>limit)return false;
   int[] swap=previous;previous=current;current=swap;
  }
  return previous[b.length()]<=limit;
 }
}
