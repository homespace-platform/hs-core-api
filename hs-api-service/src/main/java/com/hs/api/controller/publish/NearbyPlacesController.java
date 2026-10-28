package com.hs.api.controller.publish;

import com.hs.common.dto.ApiResponse;
import com.hs.listing.nearby.*;
import com.hs.listing.dto.request.AiListingSearchRequest;
import com.hs.listing.service.AiListingSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.*;

@RestController @RequiredArgsConstructor
public class NearbyPlacesController {
 private final NearbyQueryService query;private final AiListingSearchService search;
 @GetMapping("/public/listings/{id}/nearby-places")
 public ApiResponse<PlacePage> places(@PathVariable String id,@RequestParam(required=false)Integer radiusMeters,
   @RequestParam(required=false)Set<Group> groups,@RequestParam(defaultValue="1")int page,@RequestParam(required=false)Integer size) {
  return response(()->query.places(id,radiusMeters,groups,page,size));
 }
 @PostMapping("/public/listings/ai/nearby-summary")
 public ApiResponse<List<Summary>> summary(@RequestBody BatchRequest request){return response(()->query.summaries(request));}
 @GetMapping("/public/listings/ai/nearby-places/search")
 public ApiResponse<Map<String,Object>> targets(@RequestParam String keyword){
  return response(()->Map.of("places",query.targets(keyword)));
 }
 @PostMapping("/public/listings/ai/nearby-places/{id}/listings")
 public ApiResponse<com.hs.listing.dto.response.AiListingSearchResponse> reverse(@PathVariable String id,
   @RequestParam(required=false)Integer radiusMeters,@RequestBody AiListingSearchRequest request) {
  return response(()->search.search(new AiListingSearchRequest(request.filters(),request.sort(),request.limit(),request.keyword(),
    new AiListingSearchRequest.NearbyCriteria(id,null,radiusMeters))));
 }
 private static <T> ApiResponse<T> response(java.util.function.Supplier<T> operation) {
  try{return ApiResponse.<T>builder().result(operation.get()).build();}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
 }
}
