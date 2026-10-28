package com.hs.listing.nearby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import static java.util.stream.Collectors.joining;

/** Display only the requested components; provider freeform/old administrative labels stay in source metadata. */
public final class NearbyAddress {
 private static final ObjectMapper JSON=new ObjectMapper();
 private NearbyAddress() {}
 public static String display(JsonNode address) {
  String street=Stream.of(component(address,"streetNumber"),component(address,"streetName"))
   .filter(v->!v.isEmpty()).collect(joining(" "));
  String display=Stream.of(street,component(address,"municipality"),component(address,"countrySubdivision"))
   .filter(v->!v.isEmpty()).collect(joining(", "));
  return display.isEmpty()?null:display;
 }
 public static String fromStored(String address) {
  if(address==null||address.isBlank())return null;
  try{return display(JSON.readTree(address));}catch(Exception malformed){return null;}
 }
 private static String component(JsonNode address,String key) {
  if(address==null)return "";
  JsonNode value=address.path(key);
  return value.isTextual()||value.isNumber()?value.asText().strip():"";
 }
}
