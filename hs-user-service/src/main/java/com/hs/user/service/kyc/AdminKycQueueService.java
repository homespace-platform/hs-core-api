package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import com.hs.common.advice.entity.enums.ErrorCode;
import com.hs.common.context.CurrentUser;
import com.hs.user.model.constant.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
public class AdminKycQueueService {
    /** Same priority and pending-session expiry rules as KycServiceImpl.getCurrentStatus; deterministic ties. */
    public static final String CURRENT = """
        with ranked_kyc as (
          select k.*,row_number() over(partition by user_id order by (status='VERIFIED') desc,
            case when status='VERIFIED' then verified_at end desc nulls first,created_at desc nulls first,id desc) rn
          from kyc_verifications k where active=true),
        current_kyc as (
          select u.id user_id,r.name role_name,k.created_at,k.expires_at,k.retry_notice_sent_at,k.status stored_status,
            coalesce(k.provider,'VNPT') provider,
            case when k.status in ('PENDING','REVIEW_REQUIRED') and
              (k.provider<>'VNPT' or k.expires_at<=:real) then 'EXPIRED'
              else coalesce(k.status,'NOT_VERIFIED') end status,
            case when k.status='REJECTED' then case k.rejection_reason
              when 'Số định danh đã được xác minh trên tài khoản khác.' then 'DUPLICATE_ID'
              when 'Giấy tờ hoặc khuôn mặt chưa đáp ứng yêu cầu xác minh. Vui lòng thử lại.' then 'DOCUMENT_OR_FACE'
              else 'OTHER' end end rejection_category
          from users u left join roles r on r.id=u.role_id
          left join ranked_kyc k on k.user_id=u.id and k.rn=1 where u.active=true)
        """;
    public static final String OVERDUE="coalesce((stored_status in ('PENDING','REVIEW_REQUIRED') and expires_at<=:real or status in ('PENDING','REVIEW_REQUIRED') and created_at<cast(:real as timestamptz)-interval '24 hours'),false)";
    public record Query(KycStatus status,KycProvider provider,Boolean overdue,int page,int size) {
        public static Query parse(Map<String,String[]> params,boolean paginated) {
            try {
                var allowed=paginated?Set.of("status","provider","overdue","page","size"):Set.of("status","provider","overdue");
                for(var p:params.entrySet())if(!allowed.contains(p.getKey())||p.getValue().length!=1||p.getValue()[0].isBlank())throw new IllegalArgumentException();
                String overdue=value(params,"overdue",null);
                if(overdue!=null&&!Set.of("true","false").contains(overdue))throw new IllegalArgumentException();
                int page=Integer.parseInt(value(params,"page","0")),size=Integer.parseInt(value(params,"size","20"));
                if(page<0||page>10000||size<1||size>50)throw new IllegalArgumentException();
                return new Query(params.containsKey("status")?KycStatus.valueOf(value(params,"status",null)):null,
                    params.containsKey("provider")?KycProvider.valueOf(value(params,"provider",null)):null,
                    overdue==null?null:Boolean.valueOf(overdue),page,size);
            }catch(IllegalArgumentException ex){throw new AppException(ErrorCode.INVALID_REQUEST);}
        }
        private static String value(Map<String,String[]> p,String key,String fallback){return p.containsKey(key)?p.get(key)[0]:fallback;}
    }
    public record Item(String userId,String status,String provider,Instant createdAt,Instant expiresAt,String rejectionCategory) {}
    public record Page(List<Item> items,long total,int page,int size) {}
    private final NamedParameterJdbcTemplate jdbc;
    public AdminKycQueueService(JdbcTemplate shared){var template=new JdbcTemplate(Objects.requireNonNull(shared.getDataSource()));template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);}
    public static void authorize(){
        CurrentUser.requireUserId();
        var a=SecurityContextHolder.getContext().getAuthentication();
        if(a==null||!a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("ADMIN"))||!a.getAuthorities().stream().anyMatch(x->x.getAuthority().equals("KYC_VIEW")))throw new AppException(ErrorCode.PERMISSION_DENIED);
    }
    private static String filter(Query q){return " from current_kyc where true"+(q.status()==null?"":" and status=:status")+(q.provider()==null?"":" and provider=:provider")+(q.overdue()==null?"":" and "+(q.overdue()?"":"not ")+OVERDUE);}
    private static Map<String,Object> params(Query q){var p=new HashMap<String,Object>();p.put("real",Instant.now().atOffset(ZoneOffset.UTC));if(q.status()!=null)p.put("status",q.status().name());if(q.provider()!=null)p.put("provider",q.provider().name());p.put("limit",q.size());p.put("offset",q.page()*q.size());return p;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page list(Query q){authorize();var p=params(q);String where=filter(q);long total=jdbc.queryForObject(CURRENT+"select count(*)"+where,p,Long.class);
        var items=jdbc.query(CURRENT+"select user_id,status,provider,created_at,expires_at,rejection_category"+where+" order by created_at desc nulls last,user_id limit :limit offset :offset",p,(rs,n)->new Item(rs.getString(1),rs.getString(2),rs.getString(3),instant(rs.getTimestamp(4)),instant(rs.getTimestamp(5)),rs.getString(6)));
        return new Page(items,total,q.page(),q.size());}
    @Transactional(readOnly=true)
    public List<Map<String,Object>> counts(Query q){authorize();return jdbc.queryForList(CURRENT+"select status,provider,rejection_category as \"rejectionCategory\",count(*) count"+filter(q)+" group by 1,2,3 order by 1,2,3",params(q));}
    private static Instant instant(Timestamp t){return t==null?null:t.toInstant();}
}
