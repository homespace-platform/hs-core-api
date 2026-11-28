package com.hs.contract.service.statistics;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.UserContextHolder;
import com.hs.common.time.BillingTime;
import com.hs.contract.dto.statistics.*;
import com.hs.contract.dto.statistics.AdminStatisticsResponse.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.hs.contract.service.statistics.AdminStatisticsQueries.*;

@Service
public class AdminStatisticsService {
    private final AdminStatisticsQueries queries;
    private final BillingTime time;
    private record Key(AdminStatisticsQuery query, boolean payments, boolean incidents, LocalDate today) {}
    private record Cached(Instant expires, AdminStatisticsResponse response) {}
    private final Map<Key, Cached> cache = new LinkedHashMap<>();

    public AdminStatisticsService(AdminStatisticsQueries queries, BillingTime time) { this.queries=queries; this.time=time; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public AdminStatisticsResponse statistics(AdminStatisticsQuery q) {
        authorize();
        q.validate(time.today());
        Instant real=Instant.now(), business=time.now();
        var key=new Key(q, can("PAYMENT_VIEW"), can("INCIDENT_VIEW"), time.today());
        synchronized (cache) {
            var cached=cache.get(key);
            if (cached != null && cached.expires().isAfter(real)) return cached.response();
        }
        var build=new Build(q,business,real);
        var result=build.run(key.payments(),key.incidents());
        if (result.degraded().isEmpty()) synchronized (cache) {
            cache.entrySet().removeIf(e -> !e.getValue().expires().isAfter(real));
            if (cache.size() >= 32) cache.remove(cache.keySet().iterator().next());
            cache.put(key,new Cached(real.plusSeconds(q.to().isBefore(time.today()) ? 600 : 60),result));
        }
        return result;
    }
    private static boolean can(String permission) {
        var a=SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals(permission));
    }
    public static void authorize() {
        if (UserContextHolder.get() == null || SecurityContextHolder.getContext().getAuthentication() == null)
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        if (!can("ADMIN") || !can("STATISTICS_VIEW")) throw new AppException(ErrorCode.PERMISSION_DENIED);
    }

    private final class Build {
        final AdminStatisticsQuery q;
        final Instant business,real;
        final List<String> buckets;
        final Map<String,Object> kpi=new LinkedHashMap<>(), series=new LinkedHashMap<>(), breakdown=new LinkedHashMap<>(), funnel=new LinkedHashMap<>();
        final Map<String,Source> sources=new LinkedHashMap<>();
        final List<String> degraded=new ArrayList<>();
        Build(AdminStatisticsQuery q,Instant business,Instant real) {
            this.q=q; this.business=business; this.real=real; buckets=q.buckets(); series.put("buckets",buckets);
        }
        List<Map<String,Object>> read(String name,String source,String sql,String limit) {
            var rows=queries.read(sql,q,business,real);
            sources.put(name,new Source(rows.failure()==null,source,rows.failure()==null ? limit : rows.failure()));
            if (rows.failure()!=null) degraded.add(name);
            return rows.data();
        }
        Map<String,Object> one(String name,String source,String sql,String limit) {
            var rows=read(name,source,sql,limit);
            return rows==null ? null : rows.getFirst();
        }
        Map<String,Object> counts(String name,String source,String sql) {
            var rows=read(name,source,sql,null);
            if (rows==null) return null;
            var result=new LinkedHashMap<String,Object>();
            rows.forEach(r -> result.put(text(r,"dimension"),r.get("count")));
            return result;
        }
        void timeline(String name,String source,String table,String timestamp,String amount,String dimension,String where,boolean multi,String kpiName) {
            var rows=read(name,source,AdminStatisticsQueries.timeline(table,timestamp,amount,dimension,where,q),null);
            if (rows==null) { series.put(name,null); if(kpiName!=null) kpi.put(kpiName,null); if(name.equals("gmvByType"))breakdown.put("gmv",null); return; }
            var dimensions=new TreeMap<String,List<BigDecimal>>();
            if (!multi) dimensions.put("ALL",zeros());
            if (name.equals("gmvByType")) for(String d:List.of("INITIAL","RESERVATION_DEPOSIT","MONTHLY_RENT","UTILITY","OTHER_CHARGE")) dimensions.put(d,zeros());
            if (name.equals("membershipByTier")) for(String d:List.of("FREE","STARTER","PRO","BUSINESS","ADDON")) dimensions.put(d,zeros());
            BigDecimal current=BigDecimal.ZERO,previous=BigDecimal.ZERO;long transactions=0;
            for(var r:rows) {
                BigDecimal value=number(r.get("value"));
                if (r.get("period").equals("previous")) { previous=previous.add(value); continue; }
                current=current.add(value);
                transactions+=longValue(r,"transactions");
                String d=text(r,"dimension");
                int i=buckets.indexOf(text(r,"bucket"));
                if (i>=0) dimensions.computeIfAbsent(d,x->zeros()).set(i,value);
            }
            series.put(name,multi ? dimensions : dimensions.get("ALL"));
            if (kpiName!=null) kpi.put(kpiName,Map.of("value",current,"previous",previous));
            if (name.equals("gmvByType")) breakdown.put("gmv",Map.of("confirmedTransactions",transactions,"grossVnd",current,"currency","VND"));
        }
        List<BigDecimal> zeros() { return new ArrayList<>(Collections.nCopies(buckets.size(),BigDecimal.ZERO)); }
        AdminStatisticsResponse run(boolean payments,boolean incidents) {
            timeline("newUsers","users,roles","users u join roles r on r.id=u.role_id","u.created_at","1","'ALL'","u.active=true and r.name='USER'",false,"newUsers");
            timeline("newLandlords","listings","(select l.owner_id,min(l.created_at) first_at from listings l where l.active=true and l.created_at>=:previous and l.created_at<:end and not exists(select 1 from listings older where older.owner_id=l.owner_id and older.active=true and older.created_at<:previous) group by l.owner_id) first_listing","first_at","1","'ALL'","true",false,null);
            timeline("submittedListings","listings","listings","submitted_at","1","'ALL'","active=true",false,"submittedListings");
            timeline("signedContracts","contracts","contracts","signed_at","1","'ALL'","active=true and parent_contract_id is null",false,"signedContracts");
            timeline("gmvByType","payment_requests","payment_requests p","p.confirmed_at","p.total_amount","p.type",GMV.substring(GMV.indexOf("where")+6),true,"gmvVnd");
            timeline("membershipByTier","membership_orders","membership_orders","paid_at","amount_vnd","case when kind='ADDON' then 'ADDON' else coalesce(tier,'UNKNOWN') end","status='PAID'",true,"membershipRevenueVnd");
            timeline("kycAdminManual","kyc_verifications","kyc_verifications","verified_at","1","'ALL'","active=true and provider='ADMIN' and status='VERIFIED'",false,null);

            breakdown.put("listingsByStatus",counts("listingsByStatus","listings","select status dimension,count(*) count from listings where active=true and "+in("submitted_at")+" group by status"));
            breakdown.put("listingsByCity",read("listingsByCity","listings,listing_addresses","""
                    with cities as (select coalesce(nullif(trim(a.city),''),'Chưa rõ') city,count(*) count
                    from listings l left join listing_addresses a on a.listing_id=l.id and a.active=true
                    where l.active=true and l.status in ('PUBLISHED','RESERVED','RENTED') and %s group by 1),
                    ranked as (select *,row_number() over(order by count desc,city) rn from cities)
                    select case when rn<=10 then city else 'Khác' end city,sum(count) count
                    from ranked group by 1 order by count desc,city
                    """.formatted(in("l.submitted_at")),"Top ten city labels; remaining cities combined as Khác"));
            breakdown.put("listingSupply",one("listingSupply","listings","select count(*) filter(where expires_at>:real and expires_at<=:realSeven) \"expiringIn7d\", count(*) filter(where expires_at<=:real) \"overdueUnswept\" from listings where active=true and (status='PUBLISHED' or status='PENDING_REVIEW' and review_operational_status='PUBLISHED')", "Current operational snapshot on real clock"));
            breakdown.put("contractsByStatus",counts("contractsByStatus","contracts","select status dimension,count(*) count"+ROOT+" and "+in("c.created_at")+" group by status"));
            breakdown.put("contractsByKind",counts("contractsByKind","contracts","select coalesce(agreement_kind,'LEGACY_UNSPECIFIED') dimension,count(*) count"+ROOT+" and "+in("c.created_at")+" group by 1"));
            var terminations=one("terminations","contracts","select count(*) filter(where "+in("c.termination_completed_at")+" and c.termination_forced_at is null) mutual, count(*) filter(where "+in("c.termination_forced_at")+") unilateral, count(*) filter(where "+in("c.termination_declined_at")+") declined, count(*) filter(where "+in("c.termination_proposed_at")+") proposed"+ROOT+" and ("+in("c.termination_completed_at")+" or "+in("c.termination_forced_at")+" or "+in("c.termination_declined_at")+" or "+in("c.termination_proposed_at")+")", "Recorded completion excluding forced / forced / proposal / decline events; includes deposit agreements; event-window rejection ratio");
            if(terminations!=null){terminations.put("early",null);terminations.put("rejectRate",Map.of("num",terminations.get("declined"),"den",terminations.get("proposed")));}
            breakdown.put("terminations",terminations);
            sources.put("earlyTerminations",new Source(false,"contracts","No distinct early-exit completion timestamp; agreed_early_end_exclusive is an effective date"));
            breakdown.put("membershipByProvider",read("membershipByProvider","membership_orders","select coalesce(provider,'UNKNOWN') provider,count(*) orders,sum(amount_vnd) \"amountVnd\" from membership_orders where status='PAID' and "+in("paid_at")+" group by 1", "VND value at checkout, including ETH; no refund lifecycle exists"));
            breakdown.put("membershipCheckout",read("membershipCheckout","membership_orders","select coalesce(provider,'UNKNOWN') provider,count(*) created,count(*) filter(where status='PAID') paid from membership_orders where "+in("created_at")+" group by 1", "Creation cohort and current payment status"));
            breakdown.put("kycByProvider",read("kycByProvider","kyc_verifications","select provider,count(*) total,count(*) filter(where status='VERIFIED') verified,count(*) filter(where status='REJECTED') rejected,count(*) filter(where status not in ('VERIFIED','REJECTED')) pending from kyc_verifications where active=true and "+in("created_at")+" group by provider", "Pending includes all statuses other than VERIFIED/REJECTED"));
            breakdown.put("kycRejectByCategory",counts("kycRejectByCategory","kyc_verifications","select case rejection_reason when 'Số định danh đã được xác minh trên tài khoản khác.' then 'DUPLICATE_ID' when 'Giấy tờ hoặc khuôn mặt chưa đáp ứng yêu cầu xác minh. Vui lòng thử lại.' then 'DOCUMENT_OR_FACE' else 'OTHER' end dimension,count(*) count from kyc_verifications where active=true and status='REJECTED' and "+in("created_at")+" group by 1"));

            funnel.put("registeredToOnboarded",one("registeredToOnboarded","users,roles","select count(*) filter(where u.on_boarded=true) num,count(*) den"+USER+" and "+in("u.created_at"),null));
            funnel.put("registeredToKyc",one("registeredToKyc","users,roles,kyc_verifications","select count(*) filter(where exists(select 1 from kyc_verifications k where k.user_id=u.id and k.active=true and k.status='VERIFIED')) num,count(*) den"+USER+" and "+in("u.created_at"), "Current verified KYC status, registration cohort"));
            funnel.put("submittedToPublished",one("submittedToPublished","listings","select count(*) filter(where published_at is not null) num,count(*) den from listings where active=true and "+in("submitted_at"),null));
            funnel.put("contractCreatedToSigned",one("contractCreatedToSigned","contracts","select count(*) filter(where signed_at is not null) num,count(*) den"+ROOT+" and "+in("c.created_at"),null));
            funnel.put("signedToPaid",one("signedToPaid","contracts,payment_requests","select count(*) filter(where exists(select 1 from payment_requests p where p.contract_id=c.id and p.active=true and p.status='CONFIRMED' and p.type='INITIAL' and p.direction='TENANT_TO_LANDLORD' and p.currency='VND')) num,count(*) den"+ROOT+" and "+in("c.signed_at"),null));

            var rentals=counts("rentalRequests","rental_requests","select status dimension,count(*) count from rental_requests where active=true and "+in("created_at")+" group by status");
            breakdown.put("rentalRequests",rentals==null ? null : Map.of("byStatus",rentals,"acceptRate",Map.of("num",rentals.getOrDefault("ACCEPTED",0L),"den",rentals.values().stream().mapToLong(x->((Number)x).longValue()).sum())));
            var appointments=counts("appointments","viewing_appointments","select status dimension,count(*) count from viewing_appointments where active=true and "+in("created_at")+" group by status");
            breakdown.put("appointments",appointments==null ? null : Map.of("byStatus",appointments,"completedMeaning","PAST_SCHEDULE_NOT_ATTENDANCE"));
            var rentalSla=one("rentalResponseSla","rental_requests.responded_at",response("rental_requests"),"Only real first responses; legacy nulls are unmeasured, never inferred from updated_at");
            var appointmentSla=one("appointmentResponseSla","viewing_appointments.responded_at",response("viewing_appointments"),"Only real first responses; legacy nulls are unmeasured, never inferred from updated_at");
            var sla=new LinkedHashMap<String,Object>(); sla.put("available",rentalSla!=null && appointmentSla!=null); sla.put("rentalRequests",rentalSla); sla.put("appointments",appointmentSla);
            breakdown.put("responseSla",sla);
            var active=read("membershipActive","membership_periods","select case when kind='ADDON' then 'ADDON' else coalesce(tier,'UNKNOWN') end tier,count(*) count,count(*) filter(where quota-used-reserved<=0) exhausted,count(*) filter(where end_at<=:seven) \"expiring7d\" from membership_periods where start_at<=:now and end_at>:now group by 1", "Current business-clock periods, including ADDON, excluding future and expired periods");
            if(active==null) breakdown.put("membershipActive",null);
            else {var tiers=new LinkedHashMap<String,Object>(); long exhausted=0,expiring=0; for(var r:active){tiers.put(text(r,"tier"),r.get("count"));exhausted+=longValue(r,"exhausted");expiring+=longValue(r,"expiring7d");} breakdown.put("membershipActive",Map.of("byTier",tiers,"exhausted",exhausted,"expiring7d",expiring));}
            var invoices=read("invoices","monthly_invoices","select status,count(*) count,count(*) filter(where status='DRAFT' and due_at<:now) \"draftOverdue\",coalesce(sum(late_fee_amount),0) \"lateFeeVnd\" from monthly_invoices where active=true and "+in("due_at")+" group by status", "Due-date cohort; recorded late fees, not projected future fees; BillingTime overdue comparison");
            if(invoices==null) breakdown.put("invoices",null);
            else {var states=new LinkedHashMap<String,Object>(); long overdue=0; BigDecimal fees=BigDecimal.ZERO;for(var r:invoices){states.put(text(r,"status"),r.get("count"));overdue+=longValue(r,"draftOverdue");fees=fees.add(number(r.get("lateFeeVnd")));}breakdown.put("invoices",Map.of("byStatus",states,"draftOverdue",overdue,"lateFeeVnd",fees));}
            moderation();
            paymentEvents(payments);
            incidents(incidents);
            return new AdminStatisticsResponse(new Range(q.from().toString(),q.to().toString(),q.granularity().name(),BillingTime.ZONE.getId()),real,business,kpi,series,breakdown,funnel,sources,List.copyOf(degraded));
        }
        void moderation() {
            var rows=read("moderation","listing_status_history,listings",AdminStatisticsQueries.moderation(q),"Admin PENDING_REVIEW transitions only; latency unmeasured when current submitted_at is after historical decision (resubmissions)");
            if(rows==null){breakdown.put("moderation",null);return;}
            var result=new LinkedHashMap<String,Object>();
            var approved=zeros();var rejected=zeros();var violation=zeros();
            long total=0,approvedCount=0,rejectedCount=0,violationCount=0;
            for(var r:rows) {
                if(r.get("bucket")==null){result.put("observations",r.get("observations"));result.put("reviewMedianSec",r.get("medianSec"));result.put("reviewP90Sec",r.get("p90Sec"));continue;}
                int i=buckets.indexOf(text(r,"bucket")); long count=longValue(r,"count");total+=count;
                switch(text(r,"status")){case "PUBLISHED" -> {approved.set(i,number(count));approvedCount+=count;} case "REJECTED" -> {rejected.set(i,number(count));rejectedCount+=count;} case "VIOLATION" -> {violation.set(i,number(count));violationCount+=count;} default -> {}}
            }
            result.put("approved",approved);result.put("rejected",rejected);result.put("violation",violation);
            result.put("approveRate",Map.of("num",approvedCount,"den",total));result.put("rejectRate",Map.of("num",rejectedCount,"den",total));result.put("violationRate",Map.of("num",violationCount,"den",total));
            breakdown.put("moderation",result);
        }
        void paymentEvents(boolean allowed) {
            if(!allowed){ breakdown.put("paymentEvents",null);series.put("autoConfirmedRent",null);series.put("autoConfirmedRefund",null);sources.put("paymentEvents",new Source(false,"payment_events","PAYMENT_VIEW_REQUIRED"));return;}
            var rows=read("paymentEvents","payment_events,payment_requests","select "+bucket("e.created_at",q)+" bucket,count(*) filter(where e.event_type='RECEIPT_CONFIRMED' and e.actor_id='SYSTEM' and p.direction='TENANT_TO_LANDLORD') rent,count(*) filter(where e.event_type='RECEIPT_CONFIRMED' and e.actor_id='SYSTEM' and p.direction='LANDLORD_TO_TENANT') refund,count(*) filter(where e.event_type='RECEIPT_REJECTED') rejected,count(*) filter(where e.event_type='TRANSFER_REPORTED') reported from payment_events e join payment_requests p on p.id=e.payment_request_id where p.active=true and "+in("e.created_at")+" group by 1", "Event-window ratio, not a matched transfer cohort; SYSTEM is the recorded actor");
            if(rows==null){breakdown.put("paymentEvents",null);series.put("autoConfirmedRent",null);series.put("autoConfirmedRefund",null);return;}
            var rent=zeros();var refund=zeros();long num=0,den=0;for(var r:rows){int i=buckets.indexOf(text(r,"bucket"));rent.set(i,number(r.get("rent")));refund.set(i,number(r.get("refund")));num+=longValue(r,"rejected");den+=longValue(r,"reported");}
            series.put("autoConfirmedRent",rent);series.put("autoConfirmedRefund",refund);breakdown.put("paymentEvents",Map.of("rejectRate",Map.of("num",num,"den",den)));
        }
        void incidents(boolean allowed) {
            if(!allowed){breakdown.put("incidents",null);sources.put("incidents",new Source(false,"operational_incidents","INCIDENT_VIEW_REQUIRED"));return;}
            var rows=read("incidents","operational_incidents",AdminStatisticsQueries.incidents().replace("group by component","group by grouping sets ((component),())"),"Persisted monitored components only; no SmartCA/service-wide coverage or invented incidents");
            if(rows==null){breakdown.put("incidents",null);return;}
            var result=new LinkedHashMap<String,Object>();var components=new LinkedHashMap<String,Object>();var repeated=new ArrayList<String>();
            for(var r:rows){if(r.get("component")==null){result.put("open",r.get("open"));result.put("observations",r.get("observations"));result.put("mttrMedianSec",r.get("medianSec"));result.put("mttrP90Sec",r.get("p90Sec"));}else{String component=text(r,"component");components.put(component,r.get("count"));if(longValue(r,"count")>=2)repeated.add(component);}}
            Collections.sort(repeated);result.put("byComponent",components);result.put("repeatComponents",repeated);breakdown.put("incidents",result);
        }
    }
    private static String text(Map<String,Object> r,String key){return Objects.toString(r.get(key),"UNKNOWN");}
    private static long longValue(Map<String,Object> r,String key){return ((Number)r.get(key)).longValue();}
    private static BigDecimal number(Object v){return v==null?BigDecimal.ZERO:new BigDecimal(v.toString());}
}
