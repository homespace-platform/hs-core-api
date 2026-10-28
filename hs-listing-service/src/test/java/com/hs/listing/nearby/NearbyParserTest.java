package com.hs.listing.nearby;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.hs.listing.nearby.NearbyTypes.*;

class NearbyParserTest {
 @Test void parsesTheUsersCombinedResponseAndKeepsLeafCategoryIds()throws Exception {
  var root=new ObjectMapper().readTree(getClass().getResourceAsStream("/nearby/user-snapshot-combined-fifty-pois.json"));
  Page page=TomTomNearbyClient.parse(root,0);
  assertEquals(50,page.numResults());assertEquals(100,page.totalResults());assertEquals(50,page.places().size());
  assertEquals(0,page.malformedCount());assertEquals(50,page.places().stream().map(Poi::externalId).distinct().count());
  assertTrue(page.places().stream().anyMatch(p->p.categoryIds().contains("7315078")));
  assertEquals("28 Đường Lê Lợi, Hạnh Thông, Hồ Chí Minh",page.places().getFirst().address());
 }
 static JsonNode fixture() throws Exception {return new ObjectMapper().readTree(NearbyParserTest.class.getResourceAsStream("/nearby/user-snapshot-five-pois.json"));}
 @Test void parsesActualFiveRowsWithoutInventingMissingHouseNumbers()throws Exception {
  Page p=TomTomNearbyClient.parse(fixture(),0);
  assertEquals(5,p.places().size());assertEquals(100,p.totalResults());assertEquals(0,p.malformedCount());
  assertNull(p.places().get(3).address());
  assertEquals("8 Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh",p.places().getFirst().address());
  assertEquals(10.821082,p.places().getFirst().latitude());
  assertTrue(distance(10.8209195,106.6872986,10.821082,106.687152)>24);
 }
 @Test void displayAddressNeverFallsBackToOldAdministrativeLabelsOrFreeform()throws Exception {
  var json=new ObjectMapper();
  assertEquals("Đường Nguyễn Văn Bảo, Hồ Chí Minh",NearbyAddress.display(json.readTree("{\"streetName\":\" Đường Nguyễn Văn Bảo \",\"countrySubdivision\":\"Hồ Chí Minh\",\"municipalitySubdivision\":\"Phường 4\",\"freeformAddress\":\"Old district and postal code\"}")));
  assertEquals("Đường Nguyễn Văn Bảo, Hạnh Thông, Hồ Chí Minh",NearbyAddress.display(json.readTree("{\"streetName\":\"Đường Nguyễn Văn Bảo\",\"municipality\":\" Hạnh Thông \",\"countrySubdivision\":\"Hồ Chí Minh\",\"municipalitySubdivision\":\"Phường 4\",\"freeformAddress\":\"Old district and postal code\"}")));
  assertNull(NearbyAddress.display(json.readTree("{\"freeformAddress\":\"Old address\",\"municipalitySubdivision\":\"Phường 4\"}")));
  assertNull(NearbyAddress.fromStored(null));assertNull(NearbyAddress.fromStored("invalid-json"));
 }
 @Test void catalogEdgesAreVerifiedAndChildExclusionsWin(){
  var c=new NearbyCatalog();
  var parents=Map.of("7372",Set.<String>of(),"7372010",Set.of("7372"),"9362",Set.<String>of(),"9362003",Set.of("9362"));
  assertEquals(Set.of(Group.EDUCATION),c.classify(Set.of("7372010"),parents));
  assertTrue(c.classify(Set.of("7372999"),parents).isEmpty());
  assertTrue(c.classify(Set.of("9362003"),parents).isEmpty());
  assertEquals(Set.of(Group.EDUCATION),c.requestedGroups(Set.of("7372")));
 }
 @Test void invalidOffsetCannotBecomeCompleteCoverage()throws Exception {
  assertThrows(NearbyProvider.Failure.class,()->TomTomNearbyClient.parse(fixture(),100));
 }
 @Test void malformedCoordinatesAreSkippedNotDefaultedToZero()throws Exception {
  var root=fixture().deepCopy();
  ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("results").get(0)).remove("position");
  Page p=TomTomNearbyClient.parse(root,0);
  assertEquals(4,p.places().size());assertEquals(1,p.malformedCount());
 }
 @Test void policyAndRadiusFailClosed(){
  NearbySettings s=NearbyConfigurationTest.configured();assertFalse(s.readable());assertFalse(s.runnable());
  s.setQueryEnabled(true);assertThrows(IllegalStateException.class,s::validate);
  assertThrows(IllegalArgumentException.class,()->s.radius(5001));assertEquals(5000,s.radius(5000));
 }
 @Test void retryAfterIsBoundedAndMalformedValueIsSafe(){
  assertEquals(60,TomTomNearbyClient.retryAfter("60"));assertEquals(86400,TomTomNearbyClient.retryAfter("99999999"));
  assertEquals(0,TomTomNearbyClient.retryAfter("not-a-date"));
 }
 @Test void configuredProviderOriginResolvesApiPathsAndRejectsCredentialBearingUrls(){
  NearbySettings settings=new NearbySettings();
  settings.setBaseUrl("https://provider.example.invalid/");
  assertEquals("https://provider.example.invalid/search/2/poiCategories.json",settings.providerBaseUri().resolve("/search/2/poiCategories.json").toString());
  for(String invalid:List.of("http://api.tomtom.com","https://key@api.tomtom.com","https://api.tomtom.com?key=secret","https://api.tomtom.com/search/2")){
   settings.setBaseUrl(invalid);
   var error=assertThrows(IllegalStateException.class,settings::validate);
   assertFalse(error.getMessage().contains("secret"));
  }
 }
}
