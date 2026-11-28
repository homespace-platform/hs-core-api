package com.hs.contract.repository;

import com.hs.contract.dto.dashboard.LandlordDashboardResponse.*;
import com.hs.contract.dto.branch.BranchAttentionResponse.Priority;
import com.hs.contract.dto.branch.BranchOverviewResponse.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;


/** PostgreSQL aggregates and bounded projections; never select bank, identity, complaint text or customer contact data. */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class LandlordDashboardDetailsRepository {
    private final NamedParameterJdbcTemplate jdbc;

    private static String encode(String value) {
        return value == null ? "" : org.springframework.web.util.UriUtils.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static MapSqlParameterSource owner(String id) { return new MapSqlParameterSource("owner", id); }
    private static Timestamp ts(Instant at) { return Timestamp.from(at); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public Unplaced unplaced(String id) {
        return jdbc.queryForObject("""
                select (select count(*) from listings where owner_id=:owner and active and branch_id is null) listings,
                  (select count(*) from contracts c where c.landlord_id=:owner and c.active
                   and c.parent_contract_id is null and coalesce(c.agreement_kind,'LEASE')<>'DEPOSIT'
                   and not exists (select 1 from branch_contract_assignments a join property_branches b
                     on b.id=a.management_branch_id and b.owner_id=:owner and b.active
                     where a.contract_id=c.id)) contracts
                """, owner(id), (r, n) -> new Unplaced(r.getLong("listings"), r.getLong("contracts")));
    }

    private static final String EFFECTIVE = "case when status='PENDING_REVIEW' then coalesce(review_operational_status,status) else status end";
    private static final String FIX = " from listings where owner_id=:owner and active and " + EFFECTIVE + " in ('REJECTED','VIOLATION','HIDDEN') ";

    public NeedsFix needsFix(String id) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select " + EFFECTIVE + " status,count(*) n" + FIX + " group by " + EFFECTIVE,
                owner(id), (org.springframework.jdbc.core.RowCallbackHandler) r -> counts.put(r.getString("status"), r.getLong("n")));
        var items = jdbc.query("select id,title," + EFFECTIVE + " status,left(status_reason,120) reason,updated_at"
                + FIX + " order by updated_at desc nulls last,id limit 5", owner(id), (r, n) ->
                new ListingIssue(r.getString("id"), r.getString("title"), r.getString("status"), r.getString("reason"),
                        instant(r,"updated_at"), "/dashboard/properties/view?id=" + encode(r.getString("id"))));
        return new NeedsFix(counts.getOrDefault("REJECTED",0L), counts.getOrDefault("VIOLATION",0L),
                counts.getOrDefault("HIDDEN",0L), items);
    }

    public record ListingSignals(long favorites, boolean noLeads) {}
    public Map<String, ListingSignals> listingSignals(String id, List<String> ids, Instant now) {
        if (ids.isEmpty()) return Map.of();
        Map<String, ListingSignals> result = new HashMap<>();
        jdbc.query("""
                select l.id,(select count(*) from listing_favorites f where f.listing_id=l.id and f.active) favorites,
                  (l.published_at<=:old and not exists (select 1 from rental_requests r where r.listing_id=l.id and r.active)
                   and not exists (select 1 from viewing_appointments a where a.listing_id=l.id and a.active)) no_leads
                from listings l where l.owner_id=:owner and l.active and l.id in (:ids)
                """, owner(id).addValue("ids",ids).addValue("old",ts(now.minusSeconds(7*86400))),
                (org.springframework.jdbc.core.RowCallbackHandler) r -> result.put(r.getString("id"),
                        new ListingSignals(r.getLong("favorites"),r.getBoolean("no_leads"))));
        return result;
    }

    public List<Competing> competing(String id, Instant now) {
        return jdbc.query("""
                select l.id,l.title,count(*) n,min(r.respond_by) due from rental_requests r join listings l on l.id=r.listing_id
                where r.owner_id=:owner and l.owner_id=:owner and r.active and l.active and r.status='PENDING'
                  and r.respond_by>:now group by l.id,l.title having count(*)>=2 order by due,l.id limit 5
                """, owner(id).addValue("now",ts(now)), (r,n) -> new Competing(r.getString("id"),r.getString("title"),
                r.getLong("n"),instant(r,"due"),"/dashboard/rental-requests?listingId="+encode(r.getString("id"))));
    }

    public record Held(String id, String title, String renterName, Instant expiresAt) {}
    public List<Held> holds(String id, Instant now) {
        return jdbc.query("""
                select r.id,l.title,r.renter_name,r.hold_expires_at from rental_requests r join listings l on l.id=r.listing_id
                where r.owner_id=:owner and l.owner_id=:owner and r.active and l.active and r.status='ACCEPTED'
                  and r.hold_expires_at>:now order by r.hold_expires_at,r.id limit 10
                """, owner(id).addValue("now",ts(now)), (r,n) -> new Held(r.getString("id"),r.getString("title"),
                r.getString("renter_name"),instant(r,"hold_expires_at")));
    }

    private static final String COMPLAINT = " from dispute_cases d where d.active and d.subject_type='RENTAL' "
            + "and d.status<>'RESOLVED' and (d.payer_id=:owner or d.payee_id=:owner) ";
    public Complaints complaints(String id) {
        long[] counts = jdbc.queryForObject("select count(*) n,count(*) filter(where d.response_due_for=:owner) mine"
                + COMPLAINT,owner(id),(r,n)->new long[]{r.getLong("n"),r.getLong("mine")});
        var items = jdbc.query("""
                select d.id,(select c.contract_number from contracts c where c.id=d.contract_id and c.landlord_id=:owner)
                  contract_number,d.complaint_category,d.status,d.respond_by,d.response_due_for,d.rental_request_id
                """ + COMPLAINT + "order by d.respond_by nulls last,d.id limit 5",owner(id),(r,n)->new Complaint(
                r.getString("id"),r.getString("contract_number"),r.getString("complaint_category"),r.getString("status"),
                instant(r,"respond_by"),id.equals(r.getString("response_due_for"))?"YOU":"OTHER",
                "/dashboard/rental-requests?requestId="+encode(r.getString("rental_request_id"))));
        return new Complaints(counts[0],counts[1],items);
    }

    private static final String ROOT = "c.landlord_id=:owner and c.active and c.parent_contract_id is null "
            + "and coalesce(c.agreement_kind,'LEASE')<>'DEPOSIT'";
    // Same precedence and calendar-month arithmetic as ContractLeaseCalc.end (covered by PostgreSQL parity tests).
    private static final String ENDED = """
            select c.id,c.contract_number from contracts c join rental_requests r on r.id=c.rental_request_id
              and r.owner_id=c.landlord_id and r.active
            where %s and c.status in ('ACTIVE','EXPIRED') and r.move_in_date<=:today
              and coalesce(c.agreed_early_end_exclusive,
                (r.move_in_date + make_interval(months=>coalesce(c.agreed_early_billing_months,c.effective_lease_months,r.lease_months)))::date)<=:today
              and not exists (select 1 from contract_closures s where s.contract_id=c.id)
              and exists (select 1 from branch_contract_assignments a join property_branches b
                on b.id=a.management_branch_id and b.owner_id=:owner and b.active where a.contract_id=c.id)
            """.formatted(ROOT);

    public Closures closures(String id, LocalDate today) {
        Map<String,Long> by = new LinkedHashMap<>();
        for (String status:List.of("WAITING_HANDOVER","PROPOSED","REFUND_PENDING","BALANCE_PENDING","DISPUTED")) by.put(status,0L);
        jdbc.query("select s.status,count(*) n from contract_closures s join contracts c on c.id=s.contract_id where "
                +ROOT+" group by s.status",owner(id),(org.springframework.jdbc.core.RowCallbackHandler) r -> {
            if (by.containsKey(r.getString("status"))) by.put(r.getString("status"),r.getLong("n"));
        });
        long ended = jdbc.queryForObject("select count(*) from ("+ENDED+") e",owner(id).addValue("today",today),Long.class);
        return jdbc.queryForObject("select coalesce(sum(s.refund_amount),0) vnd,min(s.refund_due_at) due "
                +"from contract_closures s join contracts c on c.id=s.contract_id where "+ROOT+" and s.status='REFUND_PENDING'",
                owner(id),(r,n)->new Closures(by,ended,r.getBigDecimal("vnd"),instant(r,"due")));
    }

    private static final String SIGNING = "c.landlord_id=:owner and c.active and c.status in ('LANDLORD_SIGNATURE_PENDING','TENANT_SIGNATURE_PENDING')";
    public SignatureIssues signatureIssues(String id) {
        return jdbc.queryForObject("""
                select count(*) filter(where s.status in ('FAILED','EXPIRED','REJECTED')) failed,
                  count(*) filter(where s.status='PENDING_USER_CONFIRMATION') pending,
                  min(s.expires_at) filter(where s.status='PENDING_USER_CONFIRMATION') due
                from signature_requests s join contracts c on c.id=s.contract_id
                where s.active and s.revision_id=c.current_revision_id and
                """+SIGNING,owner(id),(r,n)->new SignatureIssues(r.getLong("failed"),r.getLong("pending"),instant(r,"due")));
    }
    public DepositAgreements depositAgreements(String id) {
        return jdbc.queryForObject("select count(*) n,min(c.tenant_signing_due_at) due from contracts c where "+SIGNING
                +" and c.agreement_kind='DEPOSIT'",owner(id),(r,n)->new DepositAgreements(r.getLong("n"),instant(r,"due")));
    }

    public record Orders(long pending, Instant expiresAt, long failed, BigDecimal paid) {}
    public Orders orders(String id, Instant now, Instant from, Instant to) {
        return jdbc.queryForObject("""
                select count(*) filter(where status='PENDING' and expires_at>:now) pending,
                  min(expires_at) filter(where status='PENDING' and expires_at>:now) due,
                  count(*) filter(where status in ('FAILED','REVIEW') and created_at>=:old and created_at<=:now) failed,
                  coalesce(sum(amount_vnd::numeric) filter(where status='PAID' and paid_at>=:from and paid_at<:to),0) paid
                from membership_orders where owner_id=:owner
                """,owner(id).addValue("now",ts(now)).addValue("old",ts(now.minusSeconds(30*86400)))
                .addValue("from",ts(from)).addValue("to",ts(to)),(r,n)->new Orders(r.getLong("pending"),instant(r,"due"),
                r.getLong("failed"),r.getBigDecimal("paid")));
    }

    public record Money(BigDecimal lateFromPrevious, long autoConfirmed, BigDecimal lateFee, BigDecimal rolled, long pdfIssues) {}
    public BigDecimal overdueAmount(String id, List<String> sources) {
        return jdbc.queryForObject("""
                select coalesce(sum(p.total_amount-coalesce((select sum(li.amount) from payment_line_items li
                  where li.payment_request_id=p.id and p.type='INITIAL' and li.type='DEPOSIT'),0)),0)
                from payment_requests p where p.payee_id=:owner and p.active and p.direction='TENANT_TO_LANDLORD'
                  and p.status in ('AWAITING_TRANSFER','REJECTED','OVERDUE')
                  and p.type in ('INITIAL','MONTHLY_RENT','UTILITY','OTHER_CHARGE')
                  and (p.id in (:sources) or p.invoice_id in (:sources))
                """,owner(id).addValue("sources",sources),BigDecimal.class);
    }
    public Money money(String id, Instant now, Instant realNow, Instant from, Instant to) {
        var p=owner(id).addValue("now",ts(now)).addValue("old",ts(realNow.minusSeconds(30*86400)))
                .addValue("from",ts(from)).addValue("to",ts(to)).addValue("pdfOld",ts(now.minusSeconds(90*86400)))
                .addValue("stuck",ts(realNow.minusSeconds(300))).addValue("real",ts(realNow));
        BigDecimal late=jdbc.queryForObject("""
                select coalesce(sum(p.total_amount-coalesce((select sum(li.amount) from payment_line_items li
                  where li.payment_request_id=p.id and li.type='DEPOSIT' and p.type='INITIAL'),0)),0)
                from payment_receipts r join payment_requests p on p.id=r.payment_request_id
                where r.landlord_id=:owner and p.payee_id=:owner and p.status='CONFIRMED'
                  and p.direction='TENANT_TO_LANDLORD' and p.type in ('INITIAL','MONTHLY_RENT','UTILITY','OTHER_CHARGE')
                  and r.issued_at>=:from and r.issued_at<:to and p.due_at<:from
                """,p,BigDecimal.class);
        long auto=jdbc.queryForObject("""
                select count(distinct p.id) from payment_events e join payment_requests p on p.id=e.payment_request_id
                where p.payee_id=:owner and p.direction='TENANT_TO_LANDLORD' and p.status='CONFIRMED'
                  and e.event_type='RECEIPT_CONFIRMED' and e.actor_id='SYSTEM' and e.created_at>=:old and e.created_at<=:real
                """,p,Long.class);
        BigDecimal fee=jdbc.queryForObject("select coalesce(sum(i.late_fee_amount),0) from monthly_invoices i join contracts c "
                +"on c.id=i.contract_id where "+ROOT+" and i.active and i.status not in ('DRAFT','CANCELLED') and i.due_at>=:from and i.due_at<:to",p,BigDecimal.class);
        // Count each carried balance once, only while its receiving invoice is unsettled; rollover chains are not summed twice.
        BigDecimal rolled=jdbc.queryForObject("""
                select coalesce(sum(i.total_amount),0) from monthly_invoices i join contracts c on c.id=i.contract_id
                join monthly_invoices target on target.id=i.rolled_to_invoice_id and target.contract_id=i.contract_id
                """+" where "+ROOT+" and i.active and target.active and i.status='ROLLED_OVER' and target.status in ('UNPAID','OVERDUE')",p,BigDecimal.class);
        long pdf=jdbc.queryForObject("""
                select count(*) from payment_receipts where landlord_id=:owner and issued_at>=:pdfOld and issued_at<=:now
                and (pdf_status='FAILED' or pdf_status='PENDING' and coalesce(created_at,issued_at)<:stuck)
                """,p,Long.class);
        return new Money(late,auto,fee,rolled,pdf);
    }

    private static final String EXTRA_WORK = """
            with work as (
              select 'DEFAULT_NOTICE' type,r.id,r.default_respond_by due,l.title label,
                '/dashboard/rental-requests?requestId='||r.id href from rental_requests r join listings l on l.id=r.listing_id
              where r.owner_id=:owner and l.owner_id=:owner and r.active and r.status='ACCEPTED'
                and r.default_notice_at is not null and r.default_completed_at is null and r.default_disputed_at is null
                and r.resolution_workflow_id is null and r.cancellation_requested_at is null
                and (r.default_notice_kind='FIRST_RENT' and exists (select 1 from payment_requests p
                  where p.rental_request_id=r.id and p.payer_id=r.renter_id and p.payee_id=:owner and p.active
                    and p.type='INITIAL' and p.status in ('AWAITING_TRANSFER','OVERDUE'))
                  or r.default_notice_kind='TENANT_SIGNING' and exists (select 1 from contracts c
                    where c.rental_request_id=r.id and c.landlord_id=:owner and c.active and c.parent_contract_id is null
                    and coalesce(c.agreement_kind,'LEASE')<>'DEPOSIT' and c.status='TENANT_SIGNATURE_PENDING'))
              union all
              select 'COMPLAINT_RESPOND',d.id,d.respond_by,null,
                '/dashboard/rental-requests?requestId='||d.rental_request_id from dispute_cases d
              where d.active and d.subject_type='RENTAL' and d.status<>'RESOLVED' and d.response_due_for=:owner
                and (d.payer_id=:owner or d.payee_id=:owner)
              union all
              select 'CLOSURE_ACTION',c.id,coalesce(s.refund_due_at,s.respond_by),c.contract_number,
                '/dashboard/landlord/contracts/'||c.id||'?tab=end' from contract_closures s join contracts c on c.id=s.contract_id
              where %s and s.status in ('WAITING_HANDOVER','PROPOSED','REFUND_PENDING','BALANCE_PENDING')
              union all
              select 'CLOSURE_ACTION',e.id,null,e.contract_number,'/dashboard/landlord/contracts/'||e.id||'?tab=end' from (%s) e
              union all
              select 'SIGNATURE_FAILED',s.id,s.expires_at,c.contract_number,
                '/dashboard/landlord/contracts/'||c.id||'?tab=sign' from signature_requests s join contracts c on c.id=s.contract_id
              where %s and s.active and s.revision_id=c.current_revision_id
                and (s.status in ('FAILED','EXPIRED','REJECTED') or s.status='PENDING_USER_CONFIRMATION' and s.expires_at<=:realSoon)
              union all
              select 'DEPOSIT_SIGN',c.id,c.tenant_signing_due_at,c.contract_number,
                '/dashboard/landlord/contracts/'||c.id||'?tab=sign' from contracts c where %s and c.agreement_kind='DEPOSIT'
            ), ranked as (select *,case when due<=case when type='SIGNATURE_FAILED' then cast(:realUrgent as timestamptz)
                else cast(:urgent as timestamptz) end then 0 when due<=case when type='SIGNATURE_FAILED'
                then cast(:realSoon as timestamptz) else cast(:soon as timestamptz) end then 1 else 2 end priority,
                due-case when type='SIGNATURE_FAILED' then cast(:realNow as timestamptz) else cast(:now as timestamptz) end remaining from work)
            """.formatted(ROOT,ENDED,SIGNING,SIGNING);
    public record ExtraWork(Map<String,Integer> groups, int urgent, List<TodoItem> items) {}
    public ExtraWork extraWork(String id, Instant now, Instant realNow, LocalDate today) {
        var p=owner(id).addValue("today",today).addValue("urgent",ts(now.plusSeconds(86400)))
                .addValue("soon",ts(now.plusSeconds(3*86400))).addValue("now",ts(now)).addValue("realNow",ts(realNow))
                .addValue("realUrgent",ts(realNow.plusSeconds(86400))).addValue("realSoon",ts(realNow.plusSeconds(3*86400)));
        Map<String,Integer> groups=new LinkedHashMap<>();
        int[] urgent={0};
        jdbc.query(EXTRA_WORK+"select type,count(*) n,count(*) filter(where priority=0) urgent from ranked group by type",
                p,(org.springframework.jdbc.core.RowCallbackHandler) r->{groups.put(r.getString("type"),r.getInt("n"));urgent[0]+=r.getInt("urgent");});
        var items=jdbc.query(EXTRA_WORK+"select * from ranked order by priority,remaining nulls last,type,id limit 10",p,
                (r,n)->new TodoItem(r.getString("type")+":"+r.getString("id"),r.getString("type"),null,r.getString("label"),
                        instant(r,"due"),"SIGNATURE_FAILED".equals(r.getString("type"))?Clock.REAL:Clock.BILLING,
                        Priority.values()[r.getInt("priority")],r.getString("href")));
        return new ExtraWork(groups,urgent[0],items);
    }
}
