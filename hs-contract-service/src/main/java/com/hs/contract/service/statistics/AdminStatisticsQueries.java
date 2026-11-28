package com.hs.contract.service.statistics;

import com.hs.contract.dto.statistics.AdminStatisticsQuery;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Fixed grouped SQL, never entity hydration or a query per result row. */
@Repository
public class AdminStatisticsQueries {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public AdminStatisticsQueries(JdbcTemplate shared) {
        jdbc = new JdbcTemplate(Objects.requireNonNull(shared.getDataSource()));
        jdbc.setQueryTimeout(5);
        named = new NamedParameterJdbcTemplate(jdbc);
    }
    public record Rows(List<Map<String, Object>> data, String failure) {}

    public Rows read(String sql, AdminStatisticsQuery q, Instant businessNow, Instant realNow) {
        Map<String, Object> p = new HashMap<>();
        p.put("start", utc(q.start())); p.put("end", utc(q.end())); p.put("previous", utc(q.previousStart()));
        p.put("now", utc(businessNow)); p.put("seven", utc(businessNow.plus(Duration.ofDays(7))));
        p.put("real", utc(realNow)); p.put("realSeven", utc(realNow.plus(Duration.ofDays(7))));
        return jdbc.execute((ConnectionCallback<Rows>) connection -> {
            Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
            try {
                var result = named.queryForList(sql, p);
                if (savepoint != null) connection.releaseSavepoint(savepoint);
                return new Rows(result, null);
            } catch (org.springframework.dao.DataAccessException ex) {
                // PostgreSQL aborts a transaction on an absent legacy column or timeout; isolate the failed block.
                if (savepoint != null) { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
                Throwable cause = ex.getMostSpecificCause();
                String state = cause instanceof SQLException s ? s.getSQLState() : null;
                return new Rows(null, "42P01".equals(state) || "42703".equals(state) ? "SOURCE_UNAVAILABLE" : "QUERY_FAILED");
            }
        });
    }
    private static OffsetDateTime utc(Instant t) { return t.atOffset(ZoneOffset.UTC); }
    static String in(String col) { return col + " >= :start and " + col + " < :end"; }
    static String bucket(String col, AdminStatisticsQuery q) {
        return "to_char(" + col + " at time zone 'Asia/Ho_Chi_Minh', '" +
                (q.granularity() == AdminStatisticsQuery.Granularity.DAY ? "YYYY-MM-DD" : "YYYY-MM") + "')";
    }
    static final String USER = " from users u join roles r on r.id=u.role_id where u.active=true and r.name='USER' ";
    static final String ROOT = " from contracts c where c.active=true and c.parent_contract_id is null ";
    static final String GMV = " from payment_requests p where p.active=true and p.status='CONFIRMED' and p.currency='VND'"
            + " and p.direction='TENANT_TO_LANDLORD' and p.type in ('INITIAL','MONTHLY_RENT','UTILITY','OTHER_CHARGE','RESERVATION_DEPOSIT') ";
    static String timeline(String table, String time, String amount, String dimension, String where, AdminStatisticsQuery q) {
        return "select " + bucket(time, q) + " bucket, case when " + time + " < :start then 'previous' else 'current' end period, "
                + dimension + " dimension, sum(" + amount + ") value, count(*) transactions from " + table + " where " + where
                + " and " + time + " >= :previous and " + time + " < :end group by 1,2,3";
    }
    static String percentile(String expression, String valid) {
        return "count(*) filter(where " + valid + ") observations, "
                + "percentile_cont(0.5) within group(order by " + expression + ") filter(where " + valid + ") \"medianSec\", "
                + "case when count(*) filter(where " + valid + ") >= 2 then percentile_cont(0.9) within group(order by "
                + expression + ") filter(where " + valid + ") end \"p90Sec\"";
    }
    static String response(String table) {
        return "select " + percentile("extract(epoch from (responded_at-created_at))", "responded_at >= created_at")
                + ", count(*) filter(where responded_at is null) unmeasured, count(*) filter(where responded_at<created_at) invalid from " + table + " where active=true and " + in("created_at");
    }
    static String incidents() {
        return "select component, count(*) count, count(*) filter(where open=true) open, "
                + percentile("extract(epoch from(resolved_at-opened_at))", "open=false and resolved_at >= opened_at")
                + " from operational_incidents where " + in("opened_at") + " group by component";
    }
    static String moderation(AdminStatisticsQuery q) {
        return "select " + bucket("h.created_at", q) + " bucket, h.to_status status, count(*) count, "
                + percentile("extract(epoch from(h.created_at-l.submitted_at))", "l.submitted_at <= h.created_at")
                + " from listing_status_history h join listings l on l.id=h.listing_id where l.active=true"
                + " and h.from_status='PENDING_REVIEW' and h.to_status in ('PUBLISHED','REJECTED','VIOLATION')"
                + " and h.changed_by_type='ADMIN' and " + in("h.created_at") + " group by grouping sets ((1,2),())";
    }
}
