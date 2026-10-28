package com.hs.api.controller.admin;

import com.hs.common.dto.ApiResponse;
import com.hs.listing.nearby.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController @RequiredArgsConstructor @PreAuthorize("hasAuthority('ADMIN')")
@RequestMapping("/admin")
public class NearbyScanAdminController {
 private final NearbySettings settings;private final NearbyRepository repository;
 @GetMapping("/nearby-scans")
 public ApiResponse<Map<String,Object>> jobs() {
  return ApiResponse.<Map<String,Object>>builder().result(Map.of("syncEnabled",settings.runnable(),"queryEnabled",settings.readable(),
   "jobs",settings.runnable()||settings.readable()?repository.jobs(100):List.of())).build();
 }
 @PostMapping("/listings/{id}/nearby-scans")
 public ApiResponse<Map<String,String>> enqueue(@PathVariable String id,@RequestParam(required=false)Integer radiusMeters){
  if(!settings.runnable())throw new ResponseStatusException(HttpStatus.CONFLICT,"Nearby sync is disabled or not licensed");
  try{UUID.fromString(id);repository.enqueue(id,settings.radius(radiusMeters));}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,e.getMessage());}
  return ApiResponse.<Map<String,String>>builder().result(Map.of("status","PENDING")).build();
 }
 @PostMapping("/nearby-scans/{id}/cancel")
 public ApiResponse<Map<String,Boolean>> cancel(@PathVariable String id) {
  if(!settings.runnable()&&!settings.readable())throw new ResponseStatusException(HttpStatus.CONFLICT,"Nearby module is disabled");
  try{UUID.fromString(id);}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid job ID");}
  return ApiResponse.<Map<String,Boolean>>builder().result(Map.of("cancelled",repository.cancel(id))).build();
 }
}
