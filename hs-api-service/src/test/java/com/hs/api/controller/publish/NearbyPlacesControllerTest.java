package com.hs.api.controller.publish;
import com.hs.listing.nearby.*;
import com.hs.listing.service.AiListingSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NearbyPlacesControllerTest {
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
