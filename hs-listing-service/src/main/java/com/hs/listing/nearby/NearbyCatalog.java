package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

@Component
public class NearbyCatalog {
 public static final String VERSION="2026-10-28-v1";
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
 public Set<String> requestCategories(Group group,Map<String,Set<String>> parents) {
  Set<String> ids=new TreeSet<>();
  mapping.forEach((id,groups)->{if(groups.contains(group)&&parents.containsKey(id)&&!excluded.contains(id))ids.add(id);});
  Set<String> redundant=new HashSet<>();
  for(String id:ids) for(String parent:ancestors(id,parents)) if(!parent.equals(id)&&ids.contains(parent))redundant.add(id);
  ids.removeAll(redundant);
  return ids;
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
