package com.hs.listing.nearby;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Opt in only to this task's disposable local Redis, never the application cache. */
@EnabledIfEnvironmentVariable(named="HS_NEARBY_TEST_REDIS_PORT",matches="57869")
class NearbyRedisBudgetTest {
 @Test void concurrentInstancesShareSlidingMinuteAndDailyBudgets()throws Exception {
  var factory=new LettuceConnectionFactory("127.0.0.1",57869);factory.afterPropertiesSet();factory.start();
  try {
   var redis=new StringRedisTemplate(factory);
   var keys=redis.keys("{nearby-tomtom}:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
   NearbySettings settings=new NearbySettings();settings.setLicenseApproved(true);settings.setLicensePolicyId("offline-test");
   settings.setSyncEnabled(true);settings.setApiKey("fixture-key-not-a-provider-key");
   settings.setRequestsPerMinute(4);settings.setRequestsPerDay(5);settings.setRequestsPerMonth(10);
   NearbyBudget one=new NearbyBudget(redis,settings),two=new NearbyBudget(redis,settings);
   var allowed=new AtomicInteger();
   try(var pool=Executors.newFixedThreadPool(8)){
    var tasks=new java.util.ArrayList<Future<?>>();
    for(int i=0;i<20;i++){NearbyBudget budget=i%2==0?one:two;tasks.add(pool.submit(()->{
     try{budget.reserve();allowed.incrementAndGet();}catch(NearbyProvider.Failure f){assertEquals("QUOTA_BUDGET",f.code());}
    }));}
    for(var task:tasks)task.get(10,TimeUnit.SECONDS);
   }
   assertEquals(4,allowed.get());
   // Entries older than a rolling minute expire, including across a clock-minute boundary.
   var members=redis.opsForZSet().range("{nearby-tomtom}:minute",0,-1);
   assertNotNull(members);
   for(String member:members)redis.opsForZSet().add("{nearby-tomtom}:minute",member,System.currentTimeMillis()-61000);
   one.reserve();
   assertEquals(1,redis.opsForZSet().size("{nearby-tomtom}:minute"));
   var failure=assertThrows(NearbyProvider.Failure.class,two::reserve);
   assertEquals("QUOTA_BUDGET",failure.code()); // Daily cap still applies after the minute rolls.
   redis.delete(redis.keys("{nearby-tomtom}:*"));
  }finally{factory.destroy();}
 }
}
