package com.hs.listing.nearby;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.hs.listing.nearby.NearbyTypes.*;

@Component @Slf4j
public class NearbyWorker {
 private final NearbySettings settings;private final NearbyRepository repo;private final NearbyProvider provider;private final NearbyCatalog catalog;
 private final AtomicBoolean wake=new AtomicBoolean(true);private Instant lastSweep=Instant.MIN;private volatile boolean ready;
 private final java.util.concurrent.ConcurrentLinkedQueue<String> hints=new java.util.concurrent.ConcurrentLinkedQueue<>();
 @org.springframework.beans.factory.annotation.Autowired(required=false) private NearbyMetrics metrics;
 public NearbyWorker(NearbySettings settings,NearbyRepository repo,NearbyProvider provider,NearbyCatalog catalog) {
  this.settings=settings;this.repo=repo;this.provider=provider;this.catalog=catalog;
 }
 @Configuration
 static class Scheduling {
  @Bean(name="nearbyScheduler") ThreadPoolTaskScheduler scheduler(){
   ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(2);scheduler.setThreadNamePrefix("nearby-");return scheduler;
  }
 }
 @EventListener(ApplicationReadyEvent.class)
 public void initialize(){
  settings.validate();
  // Hibernate and the reference-data runner complete before ApplicationReadyEvent.
  if(settings.runnable()||settings.readable())repo.validateSchema();
  ready=repo.schemaExists();
 }
 /** Never query a DB or call an upstream in the publication transaction/HTTP completion. */
 @TransactionalEventListener
 public void changed(ScanChanged event){if(settings.runnable()){if(hints.size()<1000)hints.add(event.listingId());wake.set(true);}}
 @Scheduled(fixedDelayString="${nearby.poll-delay-ms}",scheduler="nearbyScheduler")
 public void tick() {
  if(!ready||!settings.runnable())return;
  try {
   Set<String> changed=new HashSet<>();
   for(int i=0;i<25;i++){String id=hints.poll();if(id==null)break;changed.add(id);}
   for(String id:changed)repo.ensure(id,settings.getScanRadiusM());
   if(wake.getAndSet(false)||lastSweep.plusSeconds(60).isBefore(Instant.now())) {
    repo.reconcile(settings.getScanRadiusM(),25);lastSweep=Instant.now();
   }
   Job job=repo.claim();if(job!=null)process(job);
  }catch(Exception failure){log.warn("nearby_worker_failed errorClass={}",failure.getClass().getSimpleName());}
 }
 public void process(Job job) {
  long started=System.nanoTime();
  String run=null;
  try {
   Location l=repo.locations(List.of(job.listingId())).stream().findFirst().orElse(null);
   if(l==null||!l.hash().equals(job.locationHash())) {repo.finishJob(job,"SUPERSEDED",null,86400);return;}
   Set<String> requested=settings.categoryIds();
   Map<String,Set<String>> parents=provider.categoryParents();
   catalog.verifyRequested(requested,parents);
   run=repo.startRun(job,l,catalog.requestedGroups(requested),requested);
   NearbyRepository.Snapshot snapshot=repo.snapshot(run);
   // A saved response survives a crash before finishRun; never fetch it a second time.
   if(snapshot.pagesFetched()==0) {
    repo.heartbeat(job);
    Page page=provider.search(l,job.radiusM(),requested,0);
    if(page.offset()!=0||page.numResults()>settings.getResultLimit())throw new NearbyProvider.Failure("INCONSISTENT_PAGE",false);
    boolean clean=page.malformedCount()==0;Set<String> seen=new HashSet<>();
    for(Poi p:page.places()) {
     if(!seen.add(p.externalId()))clean=false;
     if(distance(l.latitude(),l.longitude(),p.latitude(),p.longitude())>job.radiusM()+1)clean=false;
     if(catalog.unverified(p.categoryIds(),parents))clean=false;
    }
    // Store the single response and deduplicate it. Missing groups never trigger extra calls.
    repo.savePage(job,l,run,page,parents,catalog,clean);
    snapshot=repo.snapshot(run);
   }
   boolean clean=snapshot.consistent();
   boolean limited=snapshot.total()!=null&&snapshot.returnedCount()<snapshot.total();
   String coverage=!clean?"PARTIAL":limited?"LIMITED_PROVIDER_RESPONSE":"COMPLETE_PROVIDER_RESPONSE";
   String reason=!clean?"INVALID_OR_UNVERIFIED_ITEMS":limited?"SINGLE_REQUEST_LIMIT":null;
   repo.finishRun(job,run,coverage,reason);
   run=null;
   // A deliberate limit is a successful scan, not a reason to schedule another page.
   repo.finishJob(job,clean?"SUCCESS":"PARTIAL",null,settings.getRetentionHours()*3600L);
  } catch(NearbyProvider.Failure failure) {
   boolean quota=failure.code().startsWith("QUOTA_")||failure.code().equals("PROVIDER_PAUSED")||failure.code().equals("TOMTOM_HTTP_429");
   boolean retry=failure.retryable()&&(quota||job.attempts()<settings.getMaxAttempts());
   if(run!=null){if(retry)repo.deferRun(run,failure.code());else repo.finishRun(job,run,"PARTIAL",failure.code());}
   repo.finishJob(job,failure.code().equals("SUPERSEDED")?"SUPERSEDED":retry?"RETRY_WAIT":"FAILED",failure.code(),Math.max(failure.retryAfterSeconds(),Math.min(3600,60L*(1L<<Math.min(job.attempts(),5)))));
   log.warn("nearby_sync_failed jobId={} code={} retry={}",job.id(),failure.code(),retry);
  } catch(Exception failure) {
   if(run!=null)repo.finishRun(job,run,"PARTIAL","INTERNAL_ERROR");
   repo.finishJob(job,job.attempts()<settings.getMaxAttempts()?"RETRY_WAIT":"FAILED","INTERNAL_ERROR",300);
   log.warn("nearby_sync_failed jobId={} errorClass={}",job.id(),failure.getClass().getSimpleName());
  }
  finally {if(metrics!=null)metrics.sync(System.nanoTime()-started);}
 }
 @Scheduled(fixedDelayString="${nearby.purge-delay-ms}",scheduler="nearbyScheduler")
 public void purge(){if(ready)try{repo.purgeExpired();}catch(Exception e){log.warn("nearby_purge_failed errorClass={}",e.getClass().getSimpleName());}}
}
