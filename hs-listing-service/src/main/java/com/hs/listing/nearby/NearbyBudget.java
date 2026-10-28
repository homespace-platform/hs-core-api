package com.hs.listing.nearby;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static com.hs.listing.nearby.NearbyProvider.Failure;

/** Redis reservation is atomic across Core instances. Fail closed on unavailable Redis. */
@Component
public class NearbyBudget {
 private final StringRedisTemplate redis; private final NearbySettings settings;
 public NearbyBudget(StringRedisTemplate redis,NearbySettings settings){this.redis=redis;this.settings=settings;}
 private static final DefaultRedisScript<Long> RESERVE=new DefaultRedisScript<>("""
  local time=redis.call('TIME')
  local now=tonumber(time[1])*1000+math.floor(tonumber(time[2])/1000)
  redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now-60000)
  if redis.call('ZCARD',KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
  for i=2,3 do
    if tonumber(redis.call('GET',KEYS[i]) or '0') >= tonumber(ARGV[i]) then return 0 end
  end
  redis.call('ZADD',KEYS[1],now,ARGV[6]); redis.call('PEXPIRE',KEYS[1],61000)
  for i=2,3 do
    redis.call('INCR',KEYS[i]); if redis.call('TTL',KEYS[i]) < 0 then redis.call('EXPIRE',KEYS[i],ARGV[i+2]) end
  end
  return 1
  """,Long.class);
 public void reserve() {
  if(!settings.runnable()) throw new Failure("PROVIDER_DISABLED",false);
  Instant now=Instant.now(); ZonedDateTime utc=now.atZone(ZoneOffset.UTC);
  // One shared application budget; no key or user input appears in Redis key names.
  // Sliding 60-second window prevents two bursts across a minute boundary.
  // Hash tag also keeps all three keys in one Redis Cluster slot.
  List<String> keys=List.of("{nearby-tomtom}:minute",
    "{nearby-tomtom}:day:"+utc.toLocalDate(),"{nearby-tomtom}:month:"+utc.getYear()+"-"+utc.getMonthValue());
  try {
   Long allowed=redis.execute(RESERVE,keys,String.valueOf(settings.getRequestsPerMinute()),
    String.valueOf(settings.getRequestsPerDay()),String.valueOf(settings.getRequestsPerMonth()),"172800","2764800",UUID.randomUUID().toString());
   if(!Long.valueOf(1).equals(allowed))throw new Failure("QUOTA_BUDGET",true);
  } catch(Failure e){throw e;} catch(Exception e){throw new Failure("QUOTA_STORE_UNAVAILABLE",true);}
 }
}
