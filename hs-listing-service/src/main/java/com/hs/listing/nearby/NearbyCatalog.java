package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

@Component
public class NearbyCatalog {
 public static final String VERSION="2026-10-29-v3";
 private final Map<String,Set<Group>> mapping=new LinkedHashMap<>();
 private final Set<String> excluded=new HashSet<>();
 public NearbyCatalog() {
  try(var input=getClass().getResourceAsStream("/nearby/tomtom-v2-category-mapping.json")) {
   JsonNode root=new ObjectMapper().readTree(Objects.requireNonNull(input));
   root.path("excludedBranches").forEach(n->excluded.add(n.asText()));
   root.path("categories").forEach(n->{
    Set<Group> groups=new HashSet<>(); n.path("groups").forEach(g->groups.add(Group.valueOf(g.asText())));
    mapping.put(n.path("id").asText(),groups);
   });
  } catch(Exception failure) { throw new IllegalStateException("Cannot load nearby category registry",failure); }
 }
 /** Traverses only edges returned by the actual catalog, never CSV prefixes or labels. */
 public Set<Group> classify(Set<String> ids,Map<String,Set<String>> parents) {
  Set<Group> result=EnumSet.noneOf(Group.class);
  for(String id:ids) {
   if(!parents.containsKey(id)) continue;
   Set<String> lineage=ancestors(id,parents);
   if(lineage.stream().anyMatch(excluded::contains)) continue;
   for(String ancestor:lineage) result.addAll(mapping.getOrDefault(ancestor,Set.of()));
  }
  return result;
 }
 public Set<Group> requestedGroups(Set<String> ids) {
  Set<Group> groups=EnumSet.noneOf(Group.class);
  for(String id:ids) {
   if(!mapping.containsKey(id)||mapping.get(id).isEmpty()||excluded.contains(id))
    throw new IllegalStateException("Nearby category ID has no approved group mapping: "+id);
   groups.addAll(mapping.get(id));
  }
  return groups;
 }
 public void verifyRequested(Set<String> ids,Map<String,Set<String>> parents) {
  requestedGroups(ids);
  if(!parents.keySet().containsAll(ids))throw new NearbyProvider.Failure("UNVERIFIED_CATEGORY",false);
 }
 public boolean unverified(Set<String> ids,Map<String,Set<String>> parents) {
  return ids.isEmpty()||!parents.keySet().containsAll(ids);
 }
 public Map<String,Set<String>> evidence(Set<String> ids,Map<String,Set<String>> parents){
  Map<String,Set<String>> proof=new TreeMap<>();
  for(String id:ids)if(parents.containsKey(id))for(String node:ancestors(id,parents))proof.put(node,parents.getOrDefault(node,Set.of()));
  return proof;
 }
 private static Set<String> ancestors(String id,Map<String,Set<String>> parents) {
  Set<String> seen=new HashSet<>(); Deque<String> next=new ArrayDeque<>(); next.add(id);
  while(!next.isEmpty()&&seen.size()<1000) {String value=next.removeFirst();if(seen.add(value))next.addAll(parents.getOrDefault(value,Set.of()));}
  return seen;
 }
}
