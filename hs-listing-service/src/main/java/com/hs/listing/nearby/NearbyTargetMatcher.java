package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

/** Bounded matching against actual cached names/addresses; no list of landmark aliases. */
final class NearbyTargetMatcher {
 private NearbyTargetMatcher() {}
 private static final Set<String> STRUCTURAL=Set.of("truong","thanh","pho");
 record Search(String name,String address,List<String> tokens) {}
 record Candidate(Target target,Set<String> categories) {}
 private record Ranked(Candidate candidate,int nameScore,int addressScore) {}

 static Search parse(String keyword) {
  String[] parts=keyword.strip().split("\\s+[—–]\\s*|\\s+-\\s+|,",2);
  String name=NearbyPoiDeduplicator.normalizedName(parts[0]);
  List<String> words=tokens(name);if(words.isEmpty())words=List.of(name.split(" +"));
  return new Search(name,parts.length>1?NearbyPoiDeduplicator.normalizedName(parts[1]):"",words);
 }
 private static List<String> tokens(String value) {
  return Arrays.stream(value.split(" +")).filter(v->!v.isBlank()&&!STRUCTURAL.contains(v)).distinct().toList();
 }
 static Set<String> categories(String json) {
  if(json==null)return Set.of();
  try {
   Set<String> result=new HashSet<>();
   var root=new ObjectMapper().readTree(json);
   if(root.isObject())root.fieldNames().forEachRemaining(result::add);
   for(var node:root) {
    if(node.isTextual())result.add(node.asText());
    else if(node.isArray())for(var parent:node)if(parent.isTextual())result.add(parent.asText());
   }
   return result;
  }catch(Exception ignored){return Set.of();}
 }
 static List<Target> select(Search search,List<Candidate> candidates,NearbySettings settings) {
  List<String> address=tokens(search.address());List<Ranked> ranked=new ArrayList<>();
  for(Candidate candidate:candidates) {
   String name=NearbyPoiDeduplicator.normalizedName(candidate.target().name());
   Set<String> words=new HashSet<>(tokens(name));
   if(!words.containsAll(search.tokens()))continue;
   int score=words.size()==search.tokens().size()?100:70;
   String source=NearbyPoiDeduplicator.normalizedName(candidate.target().address());
   Set<String> sourceWords=new HashSet<>(tokens(source));
   int addressScore=address.isEmpty()?0:(int)address.stream().filter(sourceWords::contains).count()*100/address.size();
   ranked.add(new Ranked(candidate,score,addressScore));
  }
  ranked.sort(Comparator.comparingInt(Ranked::nameScore).reversed()
    .thenComparing(Comparator.comparingInt(Ranked::addressScore).reversed())
    .thenComparing(r->r.candidate().target().id()));
  if(ranked.isEmpty())return List.of();
  // An exact name or a supplied full address may disambiguate; do not guess between campuses.
  int bestName=ranked.getFirst().nameScore(),bestAddress=ranked.getFirst().addressScore();
  List<Candidate> kept=new ArrayList<>();
  for(Ranked item:ranked) {
   if(bestName==100&&item.nameScore()<100)continue;
   if(bestAddress==100&&item.addressScore()<100)continue;
   Candidate candidate=item.candidate();
   if(kept.stream().noneMatch(other->NearbyPoiDeduplicator.samePlace(poi(candidate),poi(other),settings,Map.of())))kept.add(candidate);
  }
  return kept.stream().limit(10).map(Candidate::target).toList();
 }
 private static Poi poi(Candidate candidate) {
  Target t=candidate.target();return new Poi(t.id(),t.name(),t.latitude(),t.longitude(),t.address(),null,candidate.categories(),null,null,null);
 }
}
