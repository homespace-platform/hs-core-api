package com.hs.contract.service.analytics;

import com.hs.common.time.BillingTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Read-only aggregate SQL of the landlord analytics (PostgreSQL: AT TIME ZONE, to_char). Every query is scoped by the
 * verified owner; branch and listing filters are optional. One grouped query per block, no per-row queries, no locks.
 * Months are Asia/Ho_Chi_Minh. Deposits are never revenue: INITIAL loses its DEPOSIT lines, deposit payment types are
 * not selected.
 */
@Repository
public class LandlordAnalyticsQueries {
    /** Owner, optional branch, optional listing. */
    public record Scope(String ownerId, String branchId, String listingId) {}

    public record ReceiptMonth(String month, BigDecimal received, BigDecimal refunded, BigDecimal lateCollected) {}

    public record DueMonth(String month, BigDecimal due, BigDecimal collected) {}

    public record DebtDay(int days, int count, BigDecimal vnd) {}

    public record DebtRow(String paymentId, String contractNumber, String roomCode, String tenantName, Instant dueAt,
            BigDecimal amount, String status, String contractId, String invoiceId) {}

    public record ListingRaw(String id, String title, String branchName, String status, Instant publishedAt,
            Instant expiresAt, long viewCount, long favorites, boolean noLeads) {}

    private static final String VN = "'Asia/Ho_Chi_Minh'";
    private static final String MONTH = "to_char(%s at time zone " + VN + ", 'YYYY-MM')";
    private static final String TYPES = "('INITIAL','MONTHLY_RENT','UTILITY','OTHER_CHARGE')";
    /** Rent and fees of a payment request without the deposit lines of the first payment. */
    private static final String AMOUNT = "(p.total_amount - case when p.type in ('INITIAL','INITIAL_PAYMENT_REFUND') then coalesce((select "
            + "sum(li.amount) from payment_line_items li where li.payment_request_id = p.id and li.type in ('DEPOSIT','DEPOSIT_REFUND')), 0) "
            + "else 0 end)";
    private static final String PAYMENT_JOINS = " left join contracts c on c.id = p.contract_id and c.landlord_id = :owner"
            + " left join rental_requests rr on rr.id = p.rental_request_id and rr.owner_id = :owner"
            + " left join branch_contract_assignments a on a.contract_id = c.id"
            + " left join listings l on l.id = coalesce(p.listing_id, c.listing_id, rr.listing_id) and l.owner_id = :owner";
    private static final String INVOICE_JOINS = " join contracts c on c.id = mi.contract_id"
            + " left join branch_contract_assignments a on a.contract_id = c.id"
            + " left join listings l on l.id = c.listing_id";
    private static final String NO_LEADS = "(l.published_at <= :sevenDaysAgo "
            + "and not exists (select 1 from viewing_appointments x where x.listing_id = l.id) "
            + "and not exists (select 1 from rental_requests y where y.listing_id = l.id))";
    private static final String SIGNED_ROOT = " and c.landlord_id = :owner and c.parent_contract_id is null and (c.agreement_kind is null"
            + " or c.agreement_kind <> 'DEPOSIT') and c.signed_at is not null and c.active = true"
            + " and c.status in ('ACTIVE','EXPIRED','CLOSED','TERMINATED')";
    private static final String OPEN_DEBT = " from payment_requests p" + PAYMENT_JOINS
            + " left join branch_slots sl on sl.id = a.room_id"
            + " where p.payee_id = :owner and p.direction = 'TENANT_TO_LANDLORD' and p.active = true"
            + " and p.type in " + TYPES + " and p.status in ('AWAITING_TRANSFER','TRANSFER_REPORTED','REJECTED','OVERDUE')"
            + " and p.due_at < :now and " + AMOUNT + " > 0";

    private final NamedParameterJdbcTemplate jdbc;

    public LandlordAnalyticsQueries(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static MapSqlParameterSource params(Scope scope, Instant start, Instant end) {
        var p = new MapSqlParameterSource("owner", scope.ownerId());
        if (scope.branchId() != null) p.addValue("branchId", scope.branchId());
        if (scope.listingId() != null) p.addValue("listingId", scope.listingId());
        if (start != null) p.addValue("start", utc(start)).addValue("end", utc(end));
        return p;
    }

    private static String paymentScope(Scope s) {
        return (s.listingId() == null ? "" : " and coalesce(p.listing_id, c.listing_id, rr.listing_id) = :listingId")
                + (s.branchId() == null ? "" : " and coalesce(a.management_branch_id, c.branch_id, l.branch_id) = :branchId");
    }

    private static String invoiceScope(Scope s) {
        return (s.listingId() == null ? "" : " and c.listing_id = :listingId")
                + (s.branchId() == null ? "" : " and coalesce(a.management_branch_id, c.branch_id, l.branch_id) = :branchId");
    }

    private static String listingScope(Scope s) {
        return (s.listingId() == null ? "" : " and l.id = :listingId")
                + (s.branchId() == null ? "" : " and l.branch_id = :branchId");
    }

    public boolean listingOwned(String ownerId, String listingId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from listings where id = :id and owner_id = :owner and active = true)",
                new MapSqlParameterSource("id", listingId).addValue("owner", ownerId), Boolean.class));
    }

    /** First receipt of the owner (Vietnamese date), or null. Not range- or scope-filtered. */
    public LocalDate receiptsSince(String ownerId) {
        Timestamp first = jdbc.queryForObject("select min(issued_at) from payment_receipts where landlord_id = :owner",
                new MapSqlParameterSource("owner", ownerId), Timestamp.class);
        return first == null ? null : first.toInstant().atZone(BillingTime.ZONE).toLocalDate();
    }

    /** Receipts issued in [start, end): rent received, refunds paid back, and the part paid after the due month. */
    public List<ReceiptMonth> receipts(Scope s, Instant start, Instant end) {
        String issued = MONTH.formatted("r.issued_at");
        String due = MONTH.formatted("p.due_at");
        return jdbc.query("select " + issued + " as m, "
                + "coalesce(sum(case when p.direction = 'TENANT_TO_LANDLORD' then " + AMOUNT + " else 0 end), 0), "
                + "coalesce(sum(case when p.direction = 'LANDLORD_TO_TENANT' then " + AMOUNT + " else 0 end), 0), "
                + "coalesce(sum(case when p.direction = 'TENANT_TO_LANDLORD' and p.due_at is not null and " + due
                + " < " + issued + " then " + AMOUNT + " else 0 end), 0) "
                + "from payment_receipts r join payment_requests p on p.id = r.payment_request_id" + PAYMENT_JOINS
                + " where r.landlord_id = :owner and p.status = 'CONFIRMED' and r.issued_at >= :start and r.issued_at < :end"
                + " and ((p.direction = 'TENANT_TO_LANDLORD' and p.type in " + TYPES + ")"
                + " or (p.direction = 'LANDLORD_TO_TENANT' and p.type = 'INITIAL_PAYMENT_REFUND'))"
                + paymentScope(s) + " group by m",
                params(s, start, end),
                (rs, i) -> new ReceiptMonth(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4)));
    }

    /** Payment requests due in [start, end), not cancelled or expired, and how much of them is confirmed. */
    public List<DueMonth> due(Scope s, Instant start, Instant end) {
        return jdbc.query("select " + MONTH.formatted("p.due_at") + " as m, coalesce(sum(" + AMOUNT + "), 0), "
                + "coalesce(sum(case when p.status = 'CONFIRMED' then " + AMOUNT + " else 0 end), 0) "
                + "from payment_requests p" + PAYMENT_JOINS
                + " where p.payee_id = :owner and p.direction = 'TENANT_TO_LANDLORD' and p.active = true"
                + " and p.type in " + TYPES + " and p.status not in ('CANCELLED','EXPIRED')"
                + " and p.due_at >= :start and p.due_at < :end" + paymentScope(s) + " group by m",
                params(s, start, end),
                (rs, i) -> new DueMonth(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
    }

    /** Late fees of monthly invoices due in [start, end). Informational: already inside the invoice totals. */
    public Map<String, BigDecimal> lateFees(Scope s, Instant start, Instant end) {
        return sumByMonth("select " + MONTH.formatted("mi.due_at") + " as m, coalesce(sum(mi.late_fee_amount), 0) "
                + "from monthly_invoices mi" + INVOICE_JOINS
                + " where c.landlord_id = :owner and c.active = true and mi.active = true and mi.status not in ('DRAFT','CANCELLED')"
                + " and mi.due_at >= :start and mi.due_at < :end" + invoiceScope(s) + " group by m", params(s, start, end));
    }

    /** Debt carried into the next invoice (status ROLLED_OVER), by the month the owner deferred it. */
    public Map<String, BigDecimal> rolledOver(Scope s, Instant start, Instant end) {
        return sumByMonth("select " + MONTH.formatted("mi.deferred_at") + " as m, coalesce(sum(mi.total_amount), 0) "
                + "from monthly_invoices mi" + INVOICE_JOINS
                + " where c.landlord_id = :owner and c.active = true and mi.active = true and mi.status = 'ROLLED_OVER'"
                + " and mi.deferred_at >= :start and mi.deferred_at < :end" + invoiceScope(s) + " group by m",
                params(s, start, end));
    }

    /** The owner's paid membership orders (a cost, never mixed into receipts); not branch- or listing-scoped. */
    public Map<String, BigDecimal> membership(String ownerId, Instant start, Instant end) {
        return sumByMonth("select " + MONTH.formatted("o.paid_at") + " as m, coalesce(sum(o.amount_vnd), 0) "
                + "from membership_orders o where o.owner_id = :owner and o.status = 'PAID'"
                + " and o.paid_at >= :start and o.paid_at < :end group by m",
                params(new Scope(ownerId, null, null), start, end));
    }

    private Map<String, BigDecimal> sumByMonth(String sql, MapSqlParameterSource params) {
        Map<String, BigDecimal> out = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            out.put(rs.getString(1), rs.getBigDecimal(2));
        });
        return out;
    }

    /** Open debts past due at {@code now}, grouped by whole Vietnamese calendar days late. */
    public List<DebtDay> debtDays(Scope s, Instant now, LocalDate today) {
        return jdbc.query("select (cast(:today as date) - cast(p.due_at at time zone " + VN + " as date)) as days, "
                + "count(*), sum(" + AMOUNT + ")" + OPEN_DEBT + paymentScope(s) + " group by days",
                params(s, null, null).addValue("now", utc(now)).addValue("today", today),
                (rs, i) -> new DebtDay(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3)));
    }

    /** The biggest open debts first (then the oldest, then id). */
    public List<DebtRow> topDebts(Scope s, Instant now, int limit) {
        return jdbc.query("select p.id, c.contract_number, sl.room_code, rr.renter_name, p.due_at, " + AMOUNT
                + " as amt, p.status, c.id, p.invoice_id" + OPEN_DEBT + paymentScope(s)
                + " order by amt desc, p.due_at, p.id limit :limit",
                params(s, null, null).addValue("now", utc(now)).addValue("limit", limit),
                (rs, i) -> new DebtRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getBigDecimal(6), rs.getString(7), rs.getString(8),
                        rs.getString(9)));
    }

    private static String listingFrom(Scope s, boolean noLeadsOnly) {
        return " from listings l left join property_branches b on b.id = l.branch_id and b.owner_id = :owner"
                + " where l.owner_id = :owner and l.active = true and l.status <> 'DRAFT'" + listingScope(s)
                + (noLeadsOnly ? " and " + NO_LEADS : "");
    }

    private static OffsetDateTime sevenDaysAgo(Instant realNow) {
        return utc(realNow.minus(Duration.ofDays(7)));
    }

    public long countListings(Scope s, boolean noLeadsOnly, Instant realNow) {
        return jdbc.queryForObject("select count(*)" + listingFrom(s, noLeadsOnly),
                params(s, null, null).addValue("sevenDaysAgo", sevenDaysAgo(realNow)), Long.class);
    }

    /** One page of listings, most viewed first. */
    public List<ListingRaw> listings(Scope s, boolean noLeadsOnly, Instant realNow, int offset, int size) {
        return jdbc.query("select l.id, l.title, b.name, l.status, l.published_at, l.expires_at, l.view_count, "
                + "(select count(*) from listing_favorites f where f.listing_id = l.id and f.active = true), "
                + NO_LEADS + listingFrom(s, noLeadsOnly) + " order by l.view_count desc, l.id limit :size offset :offset",
                params(s, null, null).addValue("size", size).addValue("offset", offset)
                        .addValue("sevenDaysAgo", sevenDaysAgo(realNow)),
                (rs, i) -> new ListingRaw(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)), rs.getLong(7), rs.getLong(8),
                        rs.getBoolean(9)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Appointments (not cancelled) created in [start, end) for these listings. */
    public Map<String, Long> appointments(String ownerId, Collection<String> listingIds, Instant start, Instant end) {
        return countBy("select x.listing_id, count(*) from viewing_appointments x where x.owner_id = :owner"
                + " and x.listing_id in (:ids) and x.created_at >= :start and x.created_at < :end"
                + " and x.active = true and x.status <> 'CANCELLED' group by x.listing_id", ownerId, listingIds, start, end);
    }

    /** Rental requests (not cancelled by the system) created in [start, end) for these listings. */
    public Map<String, Long> requests(String ownerId, Collection<String> listingIds, Instant start, Instant end) {
        return countBy("select x.listing_id, count(*) from rental_requests x where x.owner_id = :owner"
                + " and x.listing_id in (:ids) and x.created_at >= :start and x.created_at < :end"
                + " and x.active = true and x.status <> 'CANCELLED_BY_SYSTEM' group by x.listing_id", ownerId, listingIds, start, end);
    }

    /** Signed root leases that came from the requests created in [start, end), by listing. */
    public Map<String, Long> contracts(String ownerId, Collection<String> listingIds, Instant start, Instant end) {
        return countBy("select x.listing_id, count(*) from rental_requests x join contracts c on c.rental_request_id = x.id"
                + " where x.owner_id = :owner and x.listing_id in (:ids) and x.created_at >= :start"
                + " and x.created_at < :end and x.active = true and x.status <> 'CANCELLED_BY_SYSTEM'" + SIGNED_ROOT
                + " group by x.listing_id", ownerId, listingIds, start, end);
    }

    private Map<String, Long> countBy(String sql, String ownerId, Collection<String> ids, Instant start, Instant end) {
        Map<String, Long> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        var p = params(new Scope(ownerId, null, null), start, end).addValue("ids", ids);
        jdbc.query(sql, p, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }

    /** First day with daily view data (any listing), or null. */
    public LocalDate viewsSince(Scope s) {
        java.sql.Date first = jdbc.queryForObject("select min(d.view_date) from listing_view_daily d join listings l on l.id = d.listing_id where l.owner_id = :owner and l.active = true" + listingScope(s),
                params(s, null, null), java.sql.Date.class);
        return first == null ? null : first.toLocalDate();
    }

    public Map<String, Long> viewsInRange(Collection<String> listingIds, LocalDate from, LocalDate to) {
        Map<String, Long> out = new HashMap<>();
        if (listingIds.isEmpty()) return out;
        jdbc.query("select listing_id, sum(views) from listing_view_daily where listing_id in (:ids)"
                        + " and view_date between :from and :to group by listing_id",
                new MapSqlParameterSource("ids", listingIds).addValue("from", from).addValue("to", to),
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    /** All-time view counter of the scoped listings. */
    public long viewsCumulative(Scope s) {
        return jdbc.queryForObject("select coalesce(sum(l.view_count), 0) from listings l where l.owner_id = :owner"
                + " and l.active = true" + listingScope(s), params(s, null, null), Long.class);
    }

    public long viewsDaily(Scope s, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("select coalesce(sum(d.views), 0) from listing_view_daily d"
                        + " join listings l on l.id = d.listing_id where l.owner_id = :owner and l.active = true"
                        + " and d.view_date between :from and :to" + listingScope(s),
                params(s, null, null).addValue("from", from).addValue("to", to), Long.class);
    }

    public List<com.hs.contract.dto.analytics.LandlordAnalyticsResponse.DailyViews> dailyViews(Scope s, LocalDate from, LocalDate to) {
        return jdbc.query("select d.view_date, sum(d.views) from listing_view_daily d join listings l on l.id=d.listing_id "
                + "where l.owner_id=:owner and l.active=true and d.view_date between :from and :to" + listingScope(s)
                + " group by d.view_date order by d.view_date",
                params(s,null,null).addValue("from",from).addValue("to",to),
                (rs,i)->new com.hs.contract.dto.analytics.LandlordAnalyticsResponse.DailyViews(rs.getDate(1).toLocalDate(),rs.getLong(2)));
    }

    public long funnelAppointments(Scope s, Instant start, Instant end) {
        return jdbc.queryForObject("select count(*) from viewing_appointments x join listings l on l.id = x.listing_id"
                + " where x.owner_id = :owner and x.created_at >= :start and x.created_at < :end"
                + " and x.active = true and x.status <> 'CANCELLED'" + listingScope(s), params(s, start, end), Long.class);
    }

    public long funnelRequests(Scope s, Instant start, Instant end) {
        return jdbc.queryForObject("select count(*) from rental_requests x join listings l on l.id = x.listing_id"
                + " where x.owner_id = :owner and x.created_at >= :start and x.created_at < :end"
                + " and x.active = true and x.status <> 'CANCELLED_BY_SYSTEM'" + listingScope(s), params(s, start, end), Long.class);
    }

    public long funnelContracts(Scope s, Instant start, Instant end) {
        return jdbc.queryForObject("select count(*) from rental_requests x join listings l on l.id = x.listing_id"
                + " join contracts c on c.rental_request_id = x.id where x.owner_id = :owner"
                + " and x.created_at >= :start and x.created_at < :end and x.active = true and x.status <> 'CANCELLED_BY_SYSTEM'"
                + SIGNED_ROOT + listingScope(s), params(s, start, end), Long.class);
    }

    public List<List<Object>> receiptExport(Scope s, Instant start, Instant end, int limit) {
        return table("select r.receipt_number, r.issued_at, b.name, sl.room_code, "
                + "coalesce(r.snapshot_json::jsonb #>> '{context,contractNumber}', c.contract_number), "
                + "case when p.direction = 'LANDLORD_TO_TENANT' then r.snapshot_json::jsonb #>> '{payee,name}' "
                + "else r.snapshot_json::jsonb #>> '{payer,name}' end, p.type, "
                + "r.snapshot_json::jsonb #>> '{context,periodLabel}', "
                + "case when p.direction = 'LANDLORD_TO_TENANT' then -1 else 1 end * " + AMOUNT + ", "
                + "p.total_amount - " + AMOUNT + ", p.transfer_reference "
                + "from payment_receipts r join payment_requests p on p.id = r.payment_request_id" + PAYMENT_JOINS
                + " left join property_branches b on b.id = coalesce(a.management_branch_id, c.branch_id, l.branch_id) and b.owner_id = :owner"
                + " left join branch_slots sl on sl.id = a.room_id"
                + " where r.landlord_id = :owner and p.status = 'CONFIRMED' and r.issued_at >= :start and r.issued_at < :end"
                + " and ((p.direction = 'TENANT_TO_LANDLORD' and p.type in " + TYPES + ")"
                + " or (p.direction = 'LANDLORD_TO_TENANT' and p.type = 'INITIAL_PAYMENT_REFUND'))"
                + paymentScope(s) + " order by r.issued_at, r.id limit :limit",
                params(s, start, end).addValue("limit", limit));
    }

    public List<List<Object>> debtExport(Scope s, Instant now, LocalDate today, int limit) {
        return table("select b.name, sl.room_code, c.contract_number, rr.renter_name, p.due_at, "
                + "cast(:today as date) - cast(p.due_at at time zone " + VN + " as date), " + AMOUNT
                + ", p.status, p.transfer_reference" + OPEN_DEBT.replace(" where p.payee_id",
                    " left join property_branches b on b.id = coalesce(a.management_branch_id, c.branch_id, l.branch_id) and b.owner_id = :owner where p.payee_id")
                + paymentScope(s) + " order by p.due_at, p.id limit :limit",
                params(s, null, null).addValue("now", utc(now)).addValue("today", today).addValue("limit", limit));
    }

    private List<List<Object>> table(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (rs, i) -> {
            List<Object> row = new ArrayList<>();
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) row.add(rs.getObject(c));
            return row;
        });
    }

    public record OccupancySnapshot(String branchId, String month, int rooms, int occupiedRoomDays, int roomDays) {}

    public List<OccupancySnapshot> occupancySnapshots(String owner, Collection<String> branchIds, LocalDate from, LocalDate to) {
        if (branchIds.isEmpty()) return List.of();
        return jdbc.query("select s.branch_id, to_char(s.month_start,'YYYY-MM'), s.rooms, s.occupied_room_days, s.room_days "
                + "from branch_occupancy_monthly s join property_branches b on b.id=s.branch_id where b.owner_id=:owner "
                + "and b.active=true and s.branch_id in (:ids) and s.month_start between :from and :to",
                new MapSqlParameterSource("owner",owner).addValue("ids",branchIds).addValue("from",from).addValue("to",to),
                (rs,i)->new OccupancySnapshot(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5)));
    }

    public void snapshot(String branch, LocalDate month, com.hs.contract.dto.analytics.LandlordAnalyticsResponse.OccupancyMonth value) {
        jdbc.update("insert into branch_occupancy_monthly(branch_id,month_start,rooms,occupied_room_days,room_days) "
                + "values(:branch,:month,:rooms,:occupied,:days) on conflict do nothing",
                new MapSqlParameterSource("branch",branch).addValue("month",month).addValue("rooms",value.rooms())
                        .addValue("occupied",value.occupiedRoomDays()).addValue("days",value.roomDays()));
    }
}
