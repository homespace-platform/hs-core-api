package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

/** Configured HTTPS origin, sanitized failures; key/URL/body never enter log or exception text. */
@Component
public class TomTomNearbyClient implements NearbyProvider {
 private final NearbySettings settings; private final NearbyBudget budget;
 private final ObjectMapper mapper=new ObjectMapper();
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private Map<String,Set<String>> parents; private Instant catalogExpires=Instant.MIN;
 private volatile Instant pausedUntil=Instant.MIN;
 @org.springframework.beans.factory.annotation.Autowired(required=false) private NearbyMetrics metrics;
 public TomTomNearbyClient(NearbySettings settings,NearbyBudget budget){this.settings=settings;this.budget=budget;}
 private JsonNode get(String path,Map<String,String> parameters) {
  if(pausedUntil.isAfter(Instant.now()))throw new Failure("PROVIDER_PAUSED",true,Math.max(1,Duration.between(Instant.now(),pausedUntil).toSeconds()));
  budget.reserve();
  long started=System.nanoTime();String metricStatus="error";
  try {
   StringJoiner query=new StringJoiner("&");query.add("key="+URLEncoder.encode(settings.getApiKey(),StandardCharsets.UTF_8));
   parameters.forEach((k,v)->query.add(k+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));
   HttpRequest req=HttpRequest.newBuilder(settings.providerBaseUri().resolve(path+"?"+query))
    .timeout(Duration.ofSeconds(8)).header("Accept","application/json").GET().build();
   HttpResponse<String> response=client.send(req,HttpResponse.BodyHandlers.ofString());
   metricStatus=String.valueOf(response.statusCode());
   if(response.statusCode()!=200) {
    long retry=retryAfter(response.headers().firstValue("Retry-After").orElse(""));
    if(response.statusCode()==401||response.statusCode()==403)pausedUntil=Instant.now().plusSeconds(900);
    throw new Failure("TOMTOM_HTTP_"+response.statusCode(),response.statusCode()==429||response.statusCode()>=500,retry);
   }
   if(response.body().length()>5_000_000)throw new Failure("PROVIDER_RESPONSE_TOO_LARGE",false);
   return mapper.readTree(response.body());
  } catch(Failure e){throw e;} catch(InterruptedException e){Thread.currentThread().interrupt();throw new Failure("PROVIDER_INTERRUPTED",true);}
  catch(Exception e){throw new Failure("PROVIDER_UNAVAILABLE",true);}
  finally {if(metrics!=null)metrics.request(path.contains("poiCategories")?"categories":"nearby",metricStatus,System.nanoTime()-started);}
 }
 @Override public synchronized Map<String,Set<String>> categoryParents() {
  if(parents!=null&&catalogExpires.isAfter(Instant.now()))return parents;
  JsonNode root=get("/search/2/poiCategories.json",Map.of("language","en-US"));
  if(!root.path("poiCategories").isArray()||root.path("poiCategories").isEmpty())throw new Failure("INVALID_CATEGORY_CATALOG",false);
  Map<String,Set<String>> graph=new HashMap<>();
  for(JsonNode category:root.path("poiCategories")) {
   String id=category.path("id").asText();if(!id.matches("\\d+"))continue;
   graph.computeIfAbsent(id,k->new HashSet<>());
   for(JsonNode child:category.path("childCategoryIds"))graph.computeIfAbsent(child.asText(),k->new HashSet<>()).add(id);
  }
  if(graph.isEmpty())throw new Failure("INVALID_CATEGORY_CATALOG",false);
  Map<String,Set<String>> immutable=new HashMap<>();graph.forEach((k,v)->immutable.put(k,Set.copyOf(v)));
  parents=Map.copyOf(immutable);catalogExpires=Instant.now().plus(Duration.ofHours(24));return parents;
 }
 @Override public Page search(Location center,int radiusM,Set<String> categories,int offset) {
  if(categories.isEmpty())throw new Failure("UNVERIFIED_CATEGORY",false);
  JsonNode root=get("/search/2/nearbySearch/.json",Map.of("lat",String.valueOf(center.latitude()),
   "lon",String.valueOf(center.longitude()),"radius",String.valueOf(radiusM),"limit","100","ofs",String.valueOf(offset),
   "countrySet","VN","categorySet",String.join(",",categories)));
  return parse(root,offset);
 }
 static Page parse(JsonNode root,int expectedOffset) {
  JsonNode summary=root.path("summary");
  if(!root.path("results").isArray()||!summary.path("numResults").canConvertToInt()||!summary.path("totalResults").canConvertToInt()
    ||!summary.path("offset").canConvertToInt()||summary.path("offset").asInt()!=expectedOffset
    ||summary.path("numResults").asInt()!=root.path("results").size()||summary.path("totalResults").asInt()<expectedOffset+summary.path("numResults").asInt())
   throw new Failure("INCONSISTENT_PAGE",true);
  List<Poi> pois=new ArrayList<>();int malformed=0;
  for(JsonNode n:root.path("results")) {
   String id=n.path("id").asText(""),name=n.path("poi").path("name").asText("");
   JsonNode lat=n.path("position").path("lat"),lon=n.path("position").path("lon");
   if(!"POI".equals(n.path("type").asText())||id.isBlank()||id.length()>500||name.isBlank()||name.length()>500
     ||!lat.isNumber()||!lon.isNumber()||!valid(lat.asDouble(),lon.asDouble())) {malformed++;continue;}
   Set<String> ids=new HashSet<>();for(JsonNode c:n.path("poi").path("categorySet"))if(c.path("id").asText().matches("\\d+"))ids.add(c.path("id").asText());
   pois.add(new Poi(id,name,lat.asDouble(),lon.asDouble(),NearbyAddress.display(n.path("address")),
    nullable(n.path("info")),Set.copyOf(ids),json(n.path("poi").path("classifications")),
    json(n.path("poi").path("categories")),json(n.path("address"))));
  }
  return new Page(expectedOffset,summary.path("numResults").asInt(),summary.path("totalResults").asInt(),List.copyOf(pois),malformed);
 }
 private static String nullable(JsonNode n){return n.isMissingNode()||n.isNull()?null:n.asText();}
 static long retryAfter(String value) {
  try{return Math.max(1,Math.min(86400,Long.parseLong(value)));}
  catch(Exception ignored){try{return Math.max(1,Math.min(86400,Duration.between(Instant.now(),ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds()));}
   catch(Exception invalid){return 0;}}
 }
 private static String json(JsonNode n){return n.isMissingNode()||n.isNull()?null:n.toString();}
}
