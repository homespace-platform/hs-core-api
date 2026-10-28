package com.hs.listing.nearby;
import java.util.Set;
import java.util.Map;
import static com.hs.listing.nearby.NearbyTypes.*;
public interface NearbyProvider {
 Page search(Location center,int radiusM,Set<String> categories,int offset);
 Map<String,Set<String>> categoryParents();
 class Failure extends RuntimeException {
  private final String code; private final boolean retryable; private final long retryAfterSeconds;
  public Failure(String code,boolean retryable) {this(code,retryable,0);}
  public Failure(String code,boolean retryable,long retryAfterSeconds) { super(code); this.code=code; this.retryable=retryable; this.retryAfterSeconds=retryAfterSeconds; }
  public String code(){return code;} public boolean retryable(){return retryable;}
  public long retryAfterSeconds(){return retryAfterSeconds;}
 }
}
