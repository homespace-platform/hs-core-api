package com.hs.listing.nearby;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
@Component
public class NearbyMetrics {
 private final MeterRegistry registry;
 public NearbyMetrics(MeterRegistry registry){this.registry=registry;}
 public void request(String endpoint,String status,long nanos){
  registry.counter("nearby.provider.requests","endpoint",endpoint,"status",status).increment();
  registry.timer("nearby.provider.duration","endpoint",endpoint).record(nanos,TimeUnit.NANOSECONDS);
 }
 public void sync(long nanos){registry.timer("nearby.sync.duration").record(nanos,TimeUnit.NANOSECONDS);}
 public void job(String state){registry.counter("nearby.jobs.completed","state",state).increment();}
}
