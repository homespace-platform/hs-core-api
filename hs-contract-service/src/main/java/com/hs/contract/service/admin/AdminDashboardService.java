package com.hs.contract.service.admin;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.admin.AdminDashboardResponse;
import com.hs.contract.dto.admin.AdminDashboardResponse.*;
import com.hs.user.service.kyc.AdminKycQueueService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.hs.contract.service.admin.AdminDashboardQueries.age;

@Service
public class AdminDashboardService {
    private final AdminDashboardQueries queries;
    private final BillingTime time;
    private final AdminServiceDiscovery discovery;
    private record Cached<T>(Instant expires,T value) {}
    private final Map<String,Cached<AdminDashboardResponse>> overviewCache=new LinkedHashMap<>();
    private final Map<String,Cached<Health>> healthCache=new LinkedHashMap<>();
    private final Map<String,Instant> refreshes=new LinkedHashMap<>();
    public AdminDashboardService(AdminDashboardQueries q,BillingTime t,AdminServiceDiscovery d){queries=q;time=t;discovery=d;}
    public static String requireAdmin(){String user=CurrentUser.requireUserId();if(!permissions().contains("ADMIN"))throw new AppException(ErrorCode.PERMISSION_DENIED);return user;}
    static Set<String> permissions(){var a=SecurityContextHolder.getContext().getAuthentication();var p=new TreeSet<String>();if(a!=null)a.getAuthorities().forEach(x->p.add(x.getAuthority()));return p;}
    private String key(Set<String> p){return String.join(",",p)+"/"+time.today();}
    private synchronized boolean refresh(String user,String kind,boolean requested,int seconds,Instant now){
        if(!requested)return false;String key=user+"/"+kind;var last=refreshes.get(key);if(last!=null&&last.plusSeconds(seconds).isAfter(now))return false;
        refreshes.entrySet().removeIf(e->e.getValue().plusSeconds(30).isBefore(now));if(refreshes.size()>=256)refreshes.remove(refreshes.keySet().iterator().next());refreshes.put(key,now);return true;
    }
    private synchronized <T> T cached(Map<String,Cached<T>> cache,String key,Instant now,boolean fresh){var c=cache.get(key);return !fresh&&c!=null&&c.expires().isAfter(now)?c.value():null;}
    private synchronized <T> void put(Map<String,Cached<T>> cache,String key,T result,Instant now,int ttl){cache.entrySet().removeIf(e->!e.getValue().expires().isAfter(now));if(cache.size()>=32)cache.remove(cache.keySet().iterator().next());cache.put(key,new Cached<>(now.plusSeconds(ttl),result));}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AdminDashboardResponse overview(boolean fresh){
        String user=requireAdmin();var p=permissions();Instant now=Instant.now();String key=key(p);
        var hit=cached(overviewCache,key,now,refresh(user,"overview",fresh,10,now));if(hit!=null)return hit;
        var b=new Build(p,time.now(),now);var attention=nulls("pendingListings","oldestPendingListingAgeSec","pendingListingEdits","pendingKyc","expiredKyc","openComplaints","overdueComplaints","escalatedUnassigned","openIncidents","stuckMoney");
        var users=nulls("newToday","new7d","daily7d","kycVerified","totalUsers","disabled","activeAdmins","stuckOnboarding");var rentals=nulls("confirmedPaymentsToday","activeContracts");
        if(p.contains("LISTING_VIEW")){var listing=b.one("listings","listings","select count(*) filter(where status='PENDING_REVIEW') \"pendingListings\", "+age("min(submitted_at) filter(where status='PENDING_REVIEW')","business")+" \"oldestPendingListingAgeSec\",count(*) filter(where pending_edit_id is not null) \"pendingListingEdits\" from listings where active=true",null);if(listing!=null)attention.putAll(listing);}
        if(p.contains("USER_VIEW")){
            var u=b.one("users","users,roles","select count(*) filter(where r.name='USER' and u.active=true and u.created_at>=:today and u.created_at<:end) \"newToday\",count(*) filter(where r.name='USER' and u.active=true and u.created_at>=:seven and u.created_at<:end) \"new7d\",count(*) filter(where r.name='USER' and u.active=false) disabled,count(*) filter(where r.name='ADMIN' and u.active=true) \"activeAdmins\",count(*) filter(where r.name='USER' and u.active=true and u.on_boarded=false and u.created_at<cast(:business as timestamptz)-interval '3 days') \"stuckOnboarding\" from users u join roles r on r.id=u.role_id",null);if(u!=null)users.putAll(u);
            var daily=b.rows("usersDaily7d","users,roles","select to_char(u.created_at at time zone 'Asia/Ho_Chi_Minh','YYYY-MM-DD') bucket,count(*) value from users u join roles r on r.id=u.role_id where u.active=true and r.name='USER' and u.created_at>=:seven and u.created_at<:end group by 1",null);users.put("daily7d",b.daily(daily));
        }
        if(p.contains("KYC_VIEW")){var k=b.kyc();if(k!=null){attention.put("pendingKyc",k.get("pending"));attention.put("expiredKyc",k.get("expired"));users.put("kycVerified",k.get("verifiedUsers"));users.put("totalUsers",k.get("totalUsers"));}}
        if(p.contains("COMPLAINT_VIEW")){var c=b.complaints("business");if(c!=null){attention.put("openComplaints",c.get("open"));attention.put("overdueComplaints",c.get("overdue"));attention.put("escalatedUnassigned",c.get("escalatedUnassigned"));}}
        if(p.contains("INCIDENT_VIEW")){var i=b.one("openIncidents","operational_incidents","select count(*) count from operational_incidents where open=true","Only components monitored by existing incident writers");if(i!=null)attention.put("openIncidents",i.get("count"));}
        if(p.contains("CONTRACT_VIEW")){var c=b.one("activeContracts","contracts","select count(*) count from contracts where active=true and parent_contract_id is null and status='ACTIVE'", "Root contracts only, includes DEPOSIT agreements");if(c!=null)rentals.put("activeContracts",c.get("count"));}
        Map<String,Object> membership=null;
        if(p.contains("STATISTICS_VIEW")){
            var c=b.one("confirmedPaymentsToday","payment_requests","select count(*) count from payment_requests where active=true and status='CONFIRMED' and currency='VND' and direction='TENANT_TO_LANDLORD' and type in ('INITIAL','RESERVATION_DEPOSIT','MONTHLY_RENT','UTILITY','OTHER_CHARGE') and confirmed_at>=:today and confirmed_at<:end", "Gross transactions, refunds excluded");if(c!=null)rentals.put("confirmedPaymentsToday",c.get("count"));
            membership=b.one("membership","membership_orders","select coalesce(sum(amount_vnd) filter(where paid_at>=:today),0) \"revenueToday\",coalesce(sum(amount_vnd) filter(where paid_at>=:month),0) \"revenueMonth\" from membership_orders where status='PAID' and paid_at>=least(:month,:seven) and paid_at<:end", "VND recorded on PLAN and ADDON orders, no refund lifecycle");
            var daily=b.rows("membershipDaily7d","membership_orders","select to_char(paid_at at time zone 'Asia/Ho_Chi_Minh','YYYY-MM-DD') bucket,sum(amount_vnd) value from membership_orders where status='PAID' and paid_at>=:seven and paid_at<:end group by 1",null);if(membership!=null)membership.put("daily7d",b.daily(daily));
        }
        Health health=null;if(p.contains("PAYMENT_VIEW")||p.contains("SYSTEM_HEALTH_VIEW"))health=healthSnapshot(p,now,false);
        if(p.contains("PAYMENT_VIEW")&&health!=null){b.sources.putAll(health.sources());b.degraded.addAll(health.degraded());var money=health.payments();if(money!=null&&List.of("membershipOrdersStuck","ethUnsettled","overpaymentsAwaitingRefund","autoConfirmOverdue").stream().allMatch(x->money.get(x)!=null))attention.put("stuckMoney",List.of("membershipOrdersStuck","ethUnsettled","overpaymentsAwaitingRefund","autoConfirmOverdue").stream().mapToLong(x->((Number)money.get(x)).longValue()).sum());}
        Map<String,Object> system=health!=null&&p.contains("SYSTEM_HEALTH_VIEW")?Map.of("level",health.level(),"reasons",health.reasons()):null;
        if(health!=null&&p.contains("SYSTEM_HEALTH_VIEW")){b.sources.putAll(health.sources());b.degraded.addAll(health.degraded());}
        var response=new AdminDashboardResponse(now,time.today(),visible(attention),visible(users),visible(rentals),membership,system,b.sources,List.copyOf(new LinkedHashSet<>(b.degraded)));
        if(response.degraded().isEmpty())put(overviewCache,key,response,now,15);return response;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Health health(boolean fresh){String user=requireAdmin();Instant now=Instant.now();return healthSnapshot(permissions(),now,refresh(user,"health",fresh,30,now));}
    private Health healthSnapshot(Set<String> p,Instant now,boolean fresh){String key=key(p);var hit=cached(healthCache,key,now,fresh);if(hit!=null)return hit;var b=new Build(p,time.now(),now);var response=b.health();if(response.degraded().isEmpty())put(healthCache,key,response,now,30);return response;}
    static Map<String,Object> nulls(String... keys){var m=new LinkedHashMap<String,Object>();for(String k:keys)m.put(k,null);return m;}
    static Map<String,Object> visible(Map<String,Object> group){return group.values().stream().allMatch(Objects::isNull)?null:group;}

    private final class Build {
        final Set<String> permissions;final Instant business,real;
        final Map<String,Source> sources=new LinkedHashMap<>();final List<String> degraded=new ArrayList<>(),reasons=new ArrayList<>();
        final Set<String> levels=new HashSet<>();boolean unknown;
        Build(Set<String> p,Instant business,Instant real){permissions=p;this.business=business;this.real=real;}
        List<Map<String,Object>> rows(String key,String source,String sql,String limit){var r=queries.read(sql,business,real);sources.put(key,new Source(r.failure()==null,source,r.failure()==null?limit:r.failure()));if(r.failure()!=null){degraded.add(key);unknown=true;}return r.rows();}
        Map<String,Object> one(String key,String source,String sql,String limit){var r=rows(key,source,sql,limit);return r==null?null:r.getFirst();}
        void unavailable(String key,String source,String reason){sources.put(key,new Source(false,source,reason));unknown=true;}
        void level(String key,String level){levels.add(level);if(!level.equals("OK"))reasons.add(key+":"+level);}
        void measured(String key,Map<String,Object> group,String level){if(group!=null){group.put("level",level);level(key,level);}}
        String level(){return levels.contains("ERROR")?"ERROR":levels.contains("WARN")?"WARN":unknown||levels.contains("UNKNOWN")||levels.isEmpty()?"UNKNOWN":"OK";}
        List<Object> daily(List<Map<String,Object>> rows){if(rows==null)return null;var byDay=new HashMap<String,Object>();rows.forEach(r->byDay.put(r.get("bucket").toString(),r.get("value")));var result=new ArrayList<Object>();var today=business.atZone(BillingTime.ZONE).toLocalDate();for(int i=6;i>=0;i--)result.add(byDay.getOrDefault(today.minusDays(i).toString(),0L));return result;}
        Map<String,Object> kyc(){return one("kyc","current KYC attempts",AdminKycQueueService.CURRENT+"select count(*) filter(where status in ('PENDING','REVIEW_REQUIRED')) pending,count(*) filter(where status='REVIEW_REQUIRED') \"reviewRequired\",count(*) filter(where status='EXPIRED') expired,"+age("min(created_at) filter(where status in ('PENDING','REVIEW_REQUIRED'))","real")+" \"oldestPendingAgeSec\",count(*) filter(where provider='VNPT' and stored_status in ('PENDING','REVIEW_REQUIRED') and expires_at<=:real and retry_notice_sent_at is null) \"retryNoticeOverdue\",count(*) filter(where role_name='USER' and status='VERIFIED') \"verifiedUsers\",count(*) filter(where role_name='USER') \"totalUsers\" from current_kyc", "Verified identity takes priority over newer pending sessions; expiry applies to pending sessions, not verified identity");}
        Map<String,Object> complaints(String clock){return one("complaints","dispute_cases","select count(*) open,count(*) filter(where status='ESCALATED' and assigned_to is null) \"escalatedUnassigned\",count(*) filter(where complaint_stage='WAITING_ADMIN') \"waitingAdmin\",count(*) filter(where respond_by<=:"+clock+") overdue,"+age("min(opened_at)",clock)+" \"oldestOpenAgeSec\","+age("min(escalated_at) filter(where status='ESCALATED' and assigned_to is null)",clock)+" \"oldestUnassignedAgeSec\" from dispute_cases where active=true and subject_type='RENTAL' and status in ('OPEN','ESCALATED')", "Rental complaints only");}
        Map<String,Set<String>> columns(){var r=rows("schemaCapabilities","information_schema.columns",AdminDashboardQueries.COLUMNS,null);var result=new HashMap<String,Set<String>>();if(r!=null)r.forEach(x->result.computeIfAbsent(x.get("table_name").toString(),k->new HashSet<>()).add(x.get("column_name").toString()));return result;}
        Health health(){
            boolean system=permissions.contains("SYSTEM_HEALTH_VIEW");Map<String,Set<String>> columns=system||permissions.contains("STATISTICS_VIEW")?columns():Map.of();
            List<AdminService> services=null;List<Map<String,Object>> outbox=null;Map<String,Object> jobs=null,signing=null,blockchain=null,payments=null,kyc=null,complaints=null,sla=null,quality=null;
            if(system){
                try{services=discovery.snapshot(real).stream().map(x->new AdminService(x.name(),x.status(),x.instances(),x.lastRenewalAgeSec(),x.registeredAt())).toList();sources.put("services",new Source(true,"Eureka client registry cache","Registration only, not readiness; at most 6 expected services and 100 inspected instances per service"));level("services",services.stream().anyMatch(x->x.status().equals("DOWN"))?"ERROR":services.stream().anyMatch(x->!x.status().equals("UP"))?"WARN":"OK");}
                catch(RuntimeException ex){unavailable("services","Eureka client registry cache","DISCOVERY_UNAVAILABLE");degraded.add("services");}
                outbox=outbox(columns);jobs=jobs(columns);
                signing=one("signing","signature_requests","select count(*) filter(where status in ('CREATED','PENDING_USER_CONFIRMATION') and expires_at<=:real or status in ('PROVIDER_SIGNED','EMBEDDING') and coalesce(processing_started_at,initiated_at,created_at)<cast(:real as timestamptz)-interval '5 minutes') stuck,count(*) filter(where status in ('FAILED','EXPIRED') and failed_at>=cast(:real as timestamptz)-interval '24 hours') \"failed24h\",count(*) filter(where status in ('FAILED','EXPIRED') and failed_at is null) \"unmeasuredFailures\","+age("min(coalesce(initiated_at,created_at)) filter(where status in ('CREATED','PENDING_USER_CONFIRMATION','PROVIDER_SIGNED','EMBEDDING'))","real")+" \"oldestOpenAgeSec\" from signature_requests where active=true", "failed24h only rows with recorded failed_at; legacy failure timestamps not inferred from updated_at");
                measured("signing",signing,n(signing,"failed24h")>0?"ERROR":n(signing,"stuck")>0?"WARN":n(signing,"unmeasuredFailures")>0?"UNKNOWN":"OK");
                blockchain=one("blockchain","contract_document_proofs,contract_documents,operational_incidents","select (select count(*) from contract_document_proofs where status='PENDING') pending,(select count(*) from contract_document_proofs where status='SUBMITTED') submitted,(select count(*) from contract_document_proofs where status='FAILED') failed,(select count(*) from contract_document_proofs where status in ('PENDING','SUBMITTED') and created_at<cast(:real as timestamptz)-interval '15 minutes') stuck,(select count(*) from contract_documents where active=true and integrity_status in ('MISMATCH','MISSING')) \"integrityMismatch\",(select count(*) from operational_incidents where component='BLOCKCHAIN' and open=true) \"openIncidents\"", "Stored proof/integrity observations only; no live RPC/integrity verification");
                measured("blockchain",blockchain,n(blockchain,"failed")+n(blockchain,"integrityMismatch")+n(blockchain,"openIncidents")>0?"ERROR":n(blockchain,"stuck")>0?"WARN":"OK");
                quality=dataQuality();
                unavailable("gateway","Gateway metrics","No protected Core-accessible gateway metric source; actuator/IP fixes not approved");
                unavailable("notificationDelivery","notification-service","Separate /notifications/admin/delivery-health endpoint owned by P6; frontend reads it independently");
                unavailable("chatAndAiMetrics","external services","No Core counters or authorized external admin source; no provider calls");
            }
            if(permissions.contains("PAYMENT_VIEW"))payments=payments();
            if(permissions.contains("KYC_VIEW")){kyc=kyc();var temp=one("kycTemporaryEvidence","kyc_provider_steps","select count(*) count from kyc_provider_steps where provider='VNPT' and created_at<cast(:real as timestamptz)-interval '1 day'", "Persisted temporary evidence only");if(kyc!=null)kyc.put("tempEvidenceOverdue",temp==null?null:temp.get("count"));measured("kyc",kyc,n(kyc,"oldestPendingAgeSec")>86400||n(kyc,"retryNoticeOverdue")>0||n(kyc,"tempEvidenceOverdue")>0?"WARN":temp==null?"UNKNOWN":"OK");}
            if(permissions.contains("COMPLAINT_VIEW")){complaints=complaints("real");measured("complaints",complaints,n(complaints,"oldestUnassignedAgeSec")>86400?"ERROR":n(complaints,"overdue")+n(complaints,"escalatedUnassigned")>0?"WARN":"OK");}
            if(permissions.contains("STATISTICS_VIEW")){
                sla=one("responseSla","rental_requests,viewing_appointments","select (select count(*) from rental_requests where active=true and status='PENDING' and created_at<cast(:real as timestamptz)-interval '24 hours') \"rentalPendingOver24h\",(select count(*) from viewing_appointments where active=true and status='PENDING' and created_at<cast(:real as timestamptz)-interval '24 hours') \"appointmentPendingOver24h\",(select count(*) from rental_requests where active=true and status='PENDING' and created_at<cast(:real as timestamptz)-interval '48 hours') \"rentalPendingOver48h\",(select count(*) from viewing_appointments where active=true and status='PENDING' and created_at<cast(:real as timestamptz)-interval '48 hours') \"appointmentPendingOver48h\"", "Pending age is not response time; actual first-response percentiles live in P5");
                if(sla!=null)sla.put("respondedAtAvailable",columns.getOrDefault("rental_requests",Set.of()).contains("responded_at")&&columns.getOrDefault("viewing_appointments",Set.of()).contains("responded_at"));
            }
            return new Health(real,level(),List.copyOf(reasons),services,outbox,jobs,signing,blockchain,payments,kyc,complaints,sla,quality,null,sources,List.copyOf(degraded));
        }
        List<Map<String,Object>> outbox(Map<String,Set<String>> schema){
            var result=new ArrayList<Map<String,Object>>();
            for(String table:List.of("contract_notification_outbox","listing_notification_outbox","appointment_notification_outbox","rental_request_notification_outbox","payment_notification_outbox","membership_notification_outbox","account_notification_outbox","notification_broadcast_outbox")){
                var columns=schema.getOrDefault(table,Set.of());if(!columns.containsAll(Set.of("created_at","sent_at"))){unavailable("outbox."+table,table,"SOURCE_UNAVAILABLE");var missing=nulls("pending","retrying","dead","oldestAgeSec","lastSentAgeSec","frozen");missing.put("table",table);missing.put("level","UNKNOWN");result.add(missing);continue;}
                boolean dead=columns.contains("failed_at"),retry=columns.contains("attempts");String pending="sent_at is null"+(dead?" and failed_at is null":"");
                var row=one("outbox."+table,table,"select count(*) filter(where "+pending+") pending,"+(retry?"count(*) filter(where "+pending+" and attempts>0)":"null::bigint")+" retrying,"+(dead?"count(*) filter(where failed_at is not null)":"null::bigint")+" dead,"+age("min(created_at) filter(where "+pending+")","real")+" \"oldestAgeSec\","+age("max(sent_at)","real")+" \"lastSentAgeSec\""+(columns.contains("recipient_cursor")?",count(*) filter(where "+pending+" and recipient_cursor is not null) \"startedBroadcasts\"":"")+" from "+table,"Pending publication only, not delivery confirmation; broadcast cursor is a user ID and is never returned");
                if(row==null)continue;row.put("table",table);if(n(row,"pending")==0)row.put("oldestAgeSec",0L);
                boolean frozen=n(row,"pending")>0&&n(row,"oldestAgeSec")>300&&(row.get("lastSentAgeSec")==null||n(row,"lastSentAgeSec")>300);row.put("frozen",frozen);
                String level=frozen||n(row,"dead")>0||n(row,"oldestAgeSec")>300?"ERROR":n(row,"oldestAgeSec")>=60?"WARN":"OK";row.put("level",level);level("outbox."+table,level);
                sources.put("outbox."+table+".attempts",new Source(retry,table,retry?null:"Attempts are not recorded; retrying is null"));sources.put("outbox."+table+".dead",new Source(dead,table,dead?null:"Dead-letter state is not recorded; dead is null"));if(!retry||!dead)unknown=true;result.add(row);
            }return result;
        }
        Map<String,Object> jobs(Map<String,Set<String>> schema){
            List<Map<String,Object>> recorded=null;
            if(schema.getOrDefault("scheduled_job_runs",Set.of()).containsAll(Set.of("job_name","last_success_at","consecutive_failures","last_error_class","expected_interval_ms"))){
                recorded=rows("jobRuns","scheduled_job_runs","select job_name job,"+age("last_success_at","real")+" \"lastSuccessAgeSec\",consecutive_failures \"consecutiveFailures\",case when last_error_class ~ '^[A-Za-z_$][A-Za-z0-9_.$]{0,119}$' then last_error_class end \"lastErrorClass\",coalesce(extract(epoch from(cast(:real as timestamptz)-last_success_at))*1000>3*expected_interval_ms+60000,true) overdue from scheduled_job_runs order by job_name limit 200", "At most 200 recorded jobs; no instrumentation is installed by this API");
                if(recorded!=null)for(var row:recorded)if(n(row,"consecutiveFailures")>=3)level("jobRuns","ERROR");else if(Boolean.TRUE.equals(row.get("overdue")))level("jobRuns","WARN");
            }else unavailable("jobRuns","scheduled_job_runs","Run capture is not installed; no job success/health can be inferred");
            var work=new ArrayList<Map<String,Object>>();
            work(work,"listing_expiry","listings","active=true and status='PUBLISHED' and expires_at<cast(:real as timestamptz)-interval '2 minutes'","expires_at");
            work(work,"rental_hold_expiry","rental_requests","active=true and status='ACCEPTED' and hold_expires_at<cast(:real as timestamptz)-interval '2 minutes' and exists(select 1 from listings l where l.id=rental_requests.listing_id and l.active=true and (l.status='RESERVED' or l.status='PENDING_REVIEW' and l.review_operational_status='RESERVED')) and not exists(select 1 from contracts c where c.rental_request_id=rental_requests.id and c.status in ('PENDING_REVIEW','LANDLORD_SIGNATURE_PENDING','TENANT_SIGNATURE_PENDING','ACTIVE')) and not exists(select 1 from dispute_cases d where d.rental_request_id=rental_requests.id and d.active_key is not null) and not exists(select 1 from payment_requests p where p.rental_request_id=rental_requests.id and p.type in ('INITIAL','RESERVATION_DEPOSIT') and (p.status in ('CONFIRMED','TRANSFER_REPORTED','REJECTED') or p.status='AWAITING_TRANSFER' and (p.due_at is null or p.due_at>:business)))","hold_expires_at");
            work(work,"appointment_expiry","viewing_appointments","active=true and status in ('PENDING','CONFIRMED') and ((appointment_date+end_time) at time zone 'Asia/Ho_Chi_Minh')<cast(:real as timestamptz)-interval '20 minutes'","((appointment_date+end_time) at time zone 'Asia/Ho_Chi_Minh')");
            work(work,"monthly_invoice_overdue","monthly_invoices","active=true and status='UNPAID' and due_at<cast(:real as timestamptz)-interval '5 minutes'","due_at");
            work(work,"renewal_response","contract_renewals","status='REQUESTED' and respond_by<cast(:real as timestamptz)-interval '2 minutes'","respond_by");
            work(work,"renewal_signing","contract_renewals","status='APPROVED' and sign_by<cast(:real as timestamptz)-interval '2 minutes'","sign_by");
            work(work,"amendment_response","contract_amendments","active=true and status in ('REQUESTED','CHANGES_REQUESTED','TENANT_REQUESTED','TENANT_ACCEPTED') and respond_by<cast(:real as timestamptz)-interval '2 minutes'","respond_by");
            work(work,"amendment_signing","contract_amendments","active=true and status='APPROVED' and sign_by<cast(:real as timestamptz)-interval '2 minutes'","sign_by");
            work(work,"tenant_signing","contracts","active=true and status='TENANT_SIGNATURE_PENDING' and tenant_signing_due_at<cast(:real as timestamptz)-interval '2 minutes'","tenant_signing_due_at");
            work(work,"closure_payment","contract_closures","status in ('REFUND_PENDING','BALANCE_PENDING') and refund_due_at<:real","refund_due_at");
            work(work,"document_generation","contract_documents","active=true and (status in ('FAILED','STALE') or status='GENERATING' and created_at<cast(:real as timestamptz)-interval '10 minutes')","created_at");
            unavailable("missingMonthlyInvoices","contracts","No persisted next-period expectation or lease end; do not infer from mutable snapshots");unavailable("leaseExpiry","contracts","No lease_end_exclusive column");unavailable("branchDigest","contract_notification_outbox","No digest receipt or job run capture");
            var result=new LinkedHashMap<String,Object>();result.put("recorded",recorded);result.put("overdueWork",work);return result;
        }
        void work(List<Map<String,Object>> work,String key,String table,String where,String timestamp){boolean deadline=Set.of("rental_hold_expiry","monthly_invoice_overdue","renewal_response","renewal_signing","amendment_response","amendment_signing","tenant_signing","closure_payment").contains(key);String clock=deadline?"business":"real";
            var row=one("jobs."+key,table,"select count(*) count,"+age("min("+timestamp+")",clock)+" \"oldestAgeSec\" from "+table+" where "+(deadline?where.replace(":real",":business"):where),deadline?"Business deadline on BillingTime, with scheduler grace":"Operational timestamp on real clock");if(row==null){row=nulls("count","oldestAgeSec");}else{if(n(row,"count")==0)row.put("oldestAgeSec",0L);if(n(row,"count")>0)level("jobs."+key,"WARN");}row.put("key",key);work.add(row);}
        Map<String,Object> payments(){
            var result=nulls("membershipOrdersStuck","legacyReview","failed24h","ethUnsettled","overpaymentsAwaitingRefund","oldestOverpaymentAgeSec","receiptPdfFailed","receiptPdfOverdue","autoConfirmOverdue","storageStuck");
            var orders=one("paymentOrders","membership_orders","select count(*) filter(where status='PENDING' and expires_at<:real) \"membershipOrdersStuck\",count(*) filter(where status='REVIEW') \"legacyReview\",count(*) filter(where status='FAILED' and created_at>=cast(:real as timestamptz)-interval '24 hours') \"failed24h\" from membership_orders where status in ('PENDING','REVIEW','FAILED')", "Failed24h is creation cohort, because no failure timestamp exists");if(orders!=null)result.putAll(orders);
            var eth=one("ethUnsettled","membership_eth_quotes,membership_orders","select count(*) count from membership_eth_quotes q join membership_orders o on o.id=q.membership_order_id where q.transaction_hash is not null and o.status<>'PAID'", "No live chain/provider calls");if(eth!=null)result.put("ethUnsettled",eth.get("count"));
            var over=one("overpayments","membership_overpayments","select count(*) \"overpaymentsAwaitingRefund\","+age("min(received_at)","real")+" \"oldestOverpaymentAgeSec\" from membership_overpayments where status='REFUND_PENDING'", "Counts only, no amount, owner or transaction identifiers");if(over!=null)result.putAll(over);
            var pdf=one("receiptPdf","payment_receipts,membership_receipts","select count(*) filter(where pdf_status='FAILED') \"receiptPdfFailed\",count(*) filter(where pdf_status='PENDING' and issued_at<cast(:real as timestamptz)-interval '5 minutes') \"receiptPdfOverdue\" from (select pdf_status,issued_at from payment_receipts union all select pdf_status,issued_at from membership_receipts) receipts",null);if(pdf!=null)result.putAll(pdf);
            var auto=one("autoConfirmOverdue","payment_requests","select count(*) count from payment_requests where active=true and status='TRANSFER_REPORTED' and confirmation_due_at<:autoNow", "Deadline comparison uses max(BillingTime, real time), matching auto-confirm schedulers");if(auto!=null)result.put("autoConfirmOverdue",auto.get("count"));
            var storage=one("paymentStorage","storage_objects","select count(*) count from storage_objects where active=true and purpose in ('PAYMENT_PROOF','PAYMENT_RECEIPT') and status='PENDING' and created_at<cast(:real as timestamptz)-interval '1 hour'",null);if(storage!=null)result.put("storageStuck",storage.get("count"));
            measured("payments",result,n(result,"receiptPdfFailed")+n(result,"autoConfirmOverdue")>0?"ERROR":result.values().stream().filter(x->x instanceof Number).mapToLong(x->((Number)x).longValue()).sum()>0?"WARN":result.entrySet().stream().anyMatch(x->!x.getKey().equals("oldestOverpaymentAgeSec")&&x.getValue()==null)?"UNKNOWN":"OK");return result;
        }
        Map<String,Object> dataQuality(){
            var result=nulls("listingTypesWithoutTemplate","stuckPublicationReservations","nearbyJobsFailed");
            if(permissions.contains("CONTRACT_TEMPLATE_VIEW")){var templates=one("templateCoverage","listings,contract_templates,contract_template_versions","select count(*) count from (select distinct category from listings where active=true and status in ('PUBLISHED','RESERVED','RENTED')) categories where not exists(select 1 from contract_templates t join contract_template_versions v on v.id=t.latest_published_version_id and v.template_id=t.id where t.active=true and t.status='ACTIVE' and t.source='SYSTEM' and coalesce(t.document_kind,'LEASE')='LEASE' and t.category=categories.category and v.active=true and v.status='PUBLISHED')", "Counts active listing categories requiring a published SYSTEM LEASE template");if(templates!=null)result.put("listingTypesWithoutTemplate",templates.get("count"));}
            var reservations=one("publicationReservations","listings","select count(*) count from listings where active=true and publication_reservation is not null and status<>'PENDING_REVIEW'",null);if(reservations!=null)result.put("stuckPublicationReservations",reservations.get("count"));
            if(permissions.contains("NEARBY_SCAN_VIEW")){var nearby=one("nearbyFailures","nearby_scan_jobs","select count(*) count from nearby_scan_jobs where status in ('FAILED','RETRY_WAIT') or last_error_code='ATTEMPTS_EXHAUSTED'", "ATTEMPTS_EXHAUSTED is an error code, not a status");if(nearby!=null)result.put("nearbyJobsFailed",nearby.get("count"));}
            unavailable("userSyncFailures","UserSyncConsumer","No persisted failure counter");measured("dataQuality",result,n(result,"listingTypesWithoutTemplate")+n(result,"stuckPublicationReservations")+n(result,"nearbyJobsFailed")>0?"WARN":"UNKNOWN");return result;
        }
    }
    private static long n(Map<String,Object> row,String key){return row==null||!(row.get(key) instanceof Number value)?0:value.longValue();}
}
