package com.hs.api.controller.publish;
import com.hs.listing.nearby.*;
import com.hs.listing.service.AiListingSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class NearbyPlacesControllerTest {
 @Test void reverseLookupKeepsTypedCriteriaContextAndPaginationInsideThePathPoiScope(){
  var service=mock(AiListingSearchService.class);
  var controller=new NearbyPlacesController(mock(NearbyQueryService.class),service);
  var where=com.hs.listing.dto.request.AiListingSearchRequest.Condition.allOf(List.of(
    com.hs.listing.dto.request.AiListingSearchRequest.Condition.leaf("rent.monthly_amount","lt","4500000"),
    com.hs.listing.dto.request.AiListingSearchRequest.Condition.leaf("occupancy.max_occupants","gte","3")));
  var context=new com.hs.listing.dto.request.AiListingSearchRequest.SearchContext(3,null,null,null,null);
  var request=new com.hs.listing.dto.request.AiListingSearchRequest(List.of(),"newest",4,null,null,2,where,null,context,
    null,List.of("00000000-0000-0000-0000-000000000001"),true);
  String target="00000000-0000-0000-0000-000000000002";
  controller.reverse(target,2000,request);
  var captured=org.mockito.ArgumentCaptor.forClass(com.hs.listing.dto.request.AiListingSearchRequest.class);
  verify(service).search(captured.capture());var forwarded=captured.getValue();
  assertEquals(target,forwarded.nearby().placeId());assertEquals(2000,forwarded.nearby().radiusMeters());
  assertEquals(where,forwarded.where());assertEquals(context,forwarded.context());assertEquals(2,forwarded.version());
  assertEquals(4,forwarded.limit());assertEquals(request.excludeIds(),forwarded.excludeIds());
 }
 private static NearbySettings disabledSettings(){
  NearbySettings settings=new NearbySettings();settings.setDefaultRadiusM(2000);settings.setMaxRadiusM(5000);settings.setPublicPageSize(10);
  settings.setCategorySet("7372,7377,7321,7326,7332005,9361009,7315,7328,9942002,9362");return settings;
 }
 @Test void disabledModuleReturnsDisabledWithoutDatabaseOrProviderCalls()throws Exception {
  NearbySettings settings=disabledSettings();NearbyRepository repository=mock(NearbyRepository.class);
  var controller=new NearbyPlacesController(new NearbyQueryService(repository,settings),mock(AiListingSearchService.class));
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(get("/public/listings/00000000-0000-0000-0000-000000000001/nearby-places"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.result.enabled").value(false))
   .andExpect(jsonPath("$.result.size").value(10)).andExpect(jsonPath("$.result.coverage.radiusRequestedMeters").value(2000));
  verifyNoInteractions(repository);
 }
 @Test void invalidBatchAndRadiusAreRejectedBeforeReadingData()throws Exception {
  NearbySettings settings=disabledSettings();NearbyRepository repository=mock(NearbyRepository.class);
  var mvc=MockMvcBuilders.standaloneSetup(new NearbyPlacesController(new NearbyQueryService(repository,settings),mock(AiListingSearchService.class))).build();
  mvc.perform(get("/public/listings/00000000-0000-0000-0000-000000000001/nearby-places?radiusMeters=50000")).andExpect(status().isBadRequest());
  mvc.perform(get("/public/listings/00000000-0000-0000-0000-000000000001/nearby-places?size=0")).andExpect(status().isBadRequest());
  mvc.perform(post("/public/listings/ai/nearby-summary").contentType("application/json").content("{\"listingIds\":[],\"radiusMeters\":3000}")).andExpect(status().isBadRequest());
  mvc.perform(post("/public/listings/ai/nearby-summary").contentType("application/json").content("{\"listingIds\":[null],\"radiusMeters\":3000}")).andExpect(status().isBadRequest());
  mvc.perform(post("/public/listings/ai/nearby-summary").contentType("application/json").content("{\"listingIds\":[\"00000000-0000-0000-0000-000000000001\"],\"groups\":[null]}")).andExpect(status().isBadRequest());
  verifyNoInteractions(repository);
 }
 @Test void oversizedPageRequestsAreCappedAtTen()throws Exception {
  NearbyRepository repository=mock(NearbyRepository.class);
  var mvc=MockMvcBuilders.standaloneSetup(new NearbyPlacesController(new NearbyQueryService(repository,disabledSettings()),mock(AiListingSearchService.class))).build();
  mvc.perform(get("/public/listings/00000000-0000-0000-0000-000000000001/nearby-places?size=20&page=2"))
   .andExpect(status().isOk()).andExpect(jsonPath("$.result.page").value(2)).andExpect(jsonPath("$.result.size").value(10));
  verifyNoInteractions(repository);
 }
}
