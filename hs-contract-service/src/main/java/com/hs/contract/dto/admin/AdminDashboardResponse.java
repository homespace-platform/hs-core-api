package com.hs.contract.dto.admin;

import java.time.*;
import java.util.*;

public record AdminDashboardResponse(Instant generatedAt,LocalDate businessDate,Map<String,Object> attention,
        Map<String,Object> users,Map<String,Object> rentals,Map<String,Object> membership,Map<String,Object> system,
        Map<String,Source> sources,List<String> degraded) {
    public record Source(boolean available,String source,String limit) {}
    public record Health(Instant generatedAt,String level,List<String> reasons,List<AdminService> services,
        List<Map<String,Object>> outbox,Map<String,Object> jobs,Map<String,Object> signing,Map<String,Object> blockchain,
        Map<String,Object> payments,Map<String,Object> kyc,Map<String,Object> complaints,Map<String,Object> responseSla,
        Map<String,Object> dataQuality,Map<String,Object> gateway,Map<String,Source> sources,List<String> degraded) {}
    public record AdminService(String name,String status,int instances,Long lastRenewalAgeSec,Instant registeredAt) {}
}
