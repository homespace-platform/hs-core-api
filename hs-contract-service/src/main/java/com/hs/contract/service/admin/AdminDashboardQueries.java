package com.hs.contract.service.admin;

import com.hs.common.time.BillingTime;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class AdminDashboardQueries {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    public AdminDashboardQueries(JdbcTemplate shared){jdbc=new JdbcTemplate(Objects.requireNonNull(shared.getDataSource()));jdbc.setQueryTimeout(2);named=new NamedParameterJdbcTemplate(jdbc);}
    public record Result(List<Map<String,Object>> rows,String failure) {}
    public Result read(String sql,Instant business,Instant real){
        var today=business.atZone(BillingTime.ZONE).toLocalDate();
        Map<String,Object> p=Map.of("business",utc(business),"real",utc(real),"autoNow",utc(business.isAfter(real)?business:real),
                "today",utc(today.atStartOfDay(BillingTime.ZONE).toInstant()),"end",utc(today.plusDays(1).atStartOfDay(BillingTime.ZONE).toInstant()),
                "seven",utc(today.minusDays(6).atStartOfDay(BillingTime.ZONE).toInstant()),"month",utc(today.withDayOfMonth(1).atStartOfDay(BillingTime.ZONE).toInstant()));
        return jdbc.execute((ConnectionCallback<Result>) c->{
            Savepoint save=c.getAutoCommit()?null:c.setSavepoint();
            try{var rows=named.queryForList(sql,p);if(save!=null)c.releaseSavepoint(save);return new Result(rows,null);}
            catch(org.springframework.dao.DataAccessException ex){
                if(save!=null){c.rollback(save);c.releaseSavepoint(save);}
                var cause=ex.getMostSpecificCause();String state=cause instanceof SQLException s?s.getSQLState():null;
                return new Result(null,Set.of("42P01","42703").contains(Objects.toString(state,""))?"SOURCE_UNAVAILABLE":"QUERY_FAILED");
            }
        });
    }
    static OffsetDateTime utc(Instant t){return t.atOffset(ZoneOffset.UTC);}
    static String age(String timestamp,String clock){return "case when "+timestamp+" is null then null else greatest(0,extract(epoch from(cast(:"+clock+" as timestamptz)-"+timestamp+")))::bigint end";}
    public static final String COLUMNS="select table_name,column_name from information_schema.columns where table_schema=current_schema() and table_name in ('scheduled_job_runs','contract_notification_outbox','listing_notification_outbox','appointment_notification_outbox','rental_request_notification_outbox','payment_notification_outbox','membership_notification_outbox','account_notification_outbox','notification_broadcast_outbox','rental_requests','viewing_appointments')";
}
