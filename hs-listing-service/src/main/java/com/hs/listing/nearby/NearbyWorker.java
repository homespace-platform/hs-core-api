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
   Map<String,Set<String>> parents=provider.categoryParents();boolean complete=true;
   for(Group group:Group.values()) {
    Set<String> requested=catalog.requestCategories(group,parents);
    run=repo.startRun(job,l,group,requested);
    if(requested.isEmpty()){repo.finishRun(run,"PARTIAL","UNVERIFIED_CATEGORY");complete=false;run=null;continue;}
    boolean finished=false,clean=true;int offset=0;Set<String> seen=new HashSet<>();Integer reported=null;
    for(int pageIndex=0;pageIndex<settings.getMaxPagesPerGroup();pageIndex++) {
     repo.heartbeat(job);
     Page page=provider.search(l,job.radiusM(),requested,offset);
     if(reported!=null&&reported!=page.totalResults())clean=false;
     reported=page.totalResults();
     int before=seen.size();for(Poi p:page.places())seen.add(p.externalId());
     repo.savePage(job,l,run,page,parents,catalog);
     clean &= page.malformedCount()==0;
     if(page.numResults()>0&&seen.size()==before){clean=false;break;}
     int next=page.offset()+page.numResults();
     if(next>=page.totalResults()){finished=true;break;}
     if(page.numResults()==0||next<=offset){clean=false;break;}
     offset=next;
    }
    boolean full=finished&&clean;complete &= full;
    repo.finishRun(run,full?"COMPLETE_PROVIDER_RESPONSE":"PARTIAL",full?null:"PAGE_BUDGET_OR_INCONSISTENT_RESPONSE");
    run=null;
   }
   repo.finishJob(job,complete?"SUCCESS":"PARTIAL",null,settings.getRetentionHours()*3600L);
  } catch(NearbyProvider.Failure failure) {
   if(run!=null)repo.finishRun(run,"PARTIAL",failure.code());
   boolean quota=failure.code().startsWith("QUOTA_")||failure.code().equals("PROVIDER_PAUSED");
   boolean retry=failure.retryable()&&(quota||job.attempts()<settings.getMaxAttempts());
   repo.finishJob(job,failure.code().equals("SUPERSEDED")?"SUPERSEDED":retry?"RETRY_WAIT":"FAILED",failure.code(),Math.max(failure.retryAfterSeconds(),Math.min(3600,60L*(1L<<Math.min(job.attempts(),5)))));
   log.warn("nearby_sync_failed jobId={} code={} retry={}",job.id(),failure.code(),retry);
  } catch(Exception failure) {
   if(run!=null)repo.finishRun(run,"PARTIAL","INTERNAL_ERROR");
   repo.finishJob(job,job.attempts()<settings.getMaxAttempts()?"RETRY_WAIT":"FAILED","INTERNAL_ERROR",300);
   log.warn("nearby_sync_failed jobId={} errorClass={}",job.id(),failure.getClass().getSimpleName());
  }
  finally {if(metrics!=null)metrics.sync(System.nanoTime()-started);}
 }
 @Scheduled(fixedDelayString="${nearby.purge-delay-ms}",scheduler="nearbyScheduler")
 public void purge(){if(ready)try{repo.purgeExpired();}catch(Exception e){log.warn("nearby_purge_failed errorClass={}",e.getClass().getSimpleName());}}
}
