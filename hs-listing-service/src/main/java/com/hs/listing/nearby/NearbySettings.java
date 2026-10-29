package com.hs.listing.nearby;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

/** Deliberately fail closed: an API key is not a storage/display/AI entitlement. */
@Component @Getter @Setter
public class NearbySettings {
 @Value("${nearby.sync-enabled}") private boolean syncEnabled;
 @Value("${nearby.query-enabled}") private boolean queryEnabled;
 @Value("${nearby.tomtom.license-approved}") private boolean licenseApproved;
 @Value("${nearby.tomtom.license-policy-id}") private String licensePolicyId;
 @Value("${nearby.tomtom.api-key}") private String apiKey;
 @Value("${nearby.tomtom.base-url}") private String baseUrl;
 @Value("${nearby.tomtom.category-set}") private String categorySet;
 @Value("${nearby.tomtom.result-limit}") private int resultLimit;
 @Value("${nearby.default-radius-m}") private int defaultRadiusM;
 @Value("${nearby.scan-radius-m}") private int scanRadiusM;
 @Value("${nearby.public-page-size}") private int publicPageSize;
 @Value("${nearby.max-radius-m}") private int maxRadiusM;
 @Value("${nearby.retention-hours}") private int retentionHours;
 @Value("${nearby.requests-per-minute}") private int requestsPerMinute;
 @Value("${nearby.requests-per-day}") private int requestsPerDay;
 @Value("${nearby.requests-per-month}") private int requestsPerMonth;
 @Value("${nearby.max-attempts}") private int maxAttempts;
 public boolean readable() { return queryEnabled && permitted(); }
 public boolean permitted() { return licenseApproved && licensePolicyId != null && !licensePolicyId.isBlank(); }
 public boolean runnable() { return syncEnabled && permitted() && apiKey != null && !apiKey.isBlank(); }
 /** One OR query for the configured categories. */
 public java.util.Set<String> categoryIds() {
  if(categorySet==null || !categorySet.matches("\\d+(,\\d+)*"))
   throw new IllegalStateException("nearby.tomtom.category-set must contain comma-separated numeric category IDs");
  var ids=new java.util.LinkedHashSet<>(java.util.List.of(categorySet.split(",")));
  if(ids.size()!=categorySet.split(",").length)throw new IllegalStateException("Duplicate nearby category IDs");
  return java.util.Collections.unmodifiableSet(ids);
 }
 /** Changing the query categories must invalidate old jobs and coverage as well as mapping changes. */
 public String scanVersion() {
  try {
   String scope=String.join(",",new java.util.TreeSet<>(categoryIds()))+"|limit="+resultLimit;
   byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));
   return NearbyCatalog.VERSION+"-"+java.util.HexFormat.of().formatHex(digest,0,8);
  }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
 /** Configurable provider origin; credentials stay in the key variable, not in the URL. */
 public java.net.URI providerBaseUri() {
  try {
   java.net.URI uri=java.net.URI.create(baseUrl.strip());
   String path=uri.getRawPath();
   if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getRawUserInfo()!=null
     || uri.getRawQuery()!=null || uri.getRawFragment()!=null || uri.getPort()>65535
     || (path!=null&&!path.isEmpty()&&!path.equals("/")))throw new IllegalArgumentException();
   return uri;
  }catch(Exception invalid){throw new IllegalStateException("nearby.tomtom.base-url must be an HTTPS origin without credentials, query, fragment or API path");}
 }
 public int radius(Integer requested) {
  int value = requested == null ? defaultRadiusM : requested;
  if(value < 100 || value > maxRadiusM) throw new IllegalArgumentException("Radius must be between 100 and " + maxRadiusM + " meters");
  return value;
 }
 @PostConstruct public void validate() {
  providerBaseUri();
  new NearbyCatalog().requestedGroups(categoryIds());
  if(defaultRadiusM<100 || scanRadiusM<defaultRadiusM || maxRadiusM<scanRadiusM || maxRadiusM>10000
    || publicPageSize<1 || publicPageSize>50 || retentionHours<0 || retentionHours>720 || resultLimit<1 || resultLimit>100
    || requestsPerMinute<1 || requestsPerDay<1 || requestsPerMonth<1 || maxAttempts<1)
   throw new IllegalStateException("Invalid nearby configuration");
  if((syncEnabled || queryEnabled) && !permitted())
   throw new IllegalStateException("Nearby requires explicit storage, reuse, AI and map rights plus a license-policy-id");
  if(syncEnabled && (apiKey==null || apiKey.isBlank())) throw new IllegalStateException("Configure TOMTOM_API_KEY on the backend before enabling sync");
 }
}
