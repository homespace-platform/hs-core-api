package com.hs.api.config;

import com.hs.contract.service.admin.AdminServiceDiscovery;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Reads the existing Eureka client cache only, never contacts registered services or exposes instance addresses. */
@Component
public class AdminDiscoverySnapshot implements AdminServiceDiscovery {
    public static final List<String> EXPECTED=List.of("hs-gateway-server","hs-core-api","hs-chat-service","hs-news-service","hs-notification-service","hs-ai-service");
    private final ObjectProvider<EurekaClient> clients;
    public AdminDiscoverySnapshot(ObjectProvider<EurekaClient> clients){this.clients=clients;}
    @Override public List<Service> snapshot(Instant now){
        var client=clients.getIfAvailable();if(client==null)throw new IllegalStateException("DISCOVERY_UNAVAILABLE");
        var apps=client.getApplications();if(apps==null)throw new IllegalStateException("DISCOVERY_UNAVAILABLE");
        var result=new ArrayList<Service>();
        for(String name:EXPECTED){
            var app=apps.getRegisteredApplications(name.toUpperCase(Locale.ROOT));var instances=app==null?List.<InstanceInfo>of():app.getInstances();
            if(instances.size()>100){result.add(new Service(name,"UNKNOWN",instances.size(),null,null));continue;}
            Long oldestRenewal=null;Instant earliestRegistered=null;boolean up=false,stale=false,unknown=false;
            for(var instance:instances){
                var lease=instance.getLeaseInfo();
                if(lease==null||lease.getRenewalTimestamp()<=0){unknown=true;continue;}
                long age=Math.max(0,Duration.between(Instant.ofEpochMilli(lease.getRenewalTimestamp()),now).getSeconds());
                oldestRenewal=oldestRenewal==null?age:Math.max(oldestRenewal,age);
                if(lease.getRegistrationTimestamp()>0){var registered=Instant.ofEpochMilli(lease.getRegistrationTimestamp());if(earliestRegistered==null||registered.isBefore(earliestRegistered))earliestRegistered=registered;}
                up|=instance.getStatus()==InstanceInfo.InstanceStatus.UP;stale|=age>90||instance.getStatus()!=InstanceInfo.InstanceStatus.UP;
            }
            String status=instances.isEmpty()?"DOWN":!up&&!unknown?"DOWN":unknown?"UNKNOWN":stale?"DEGRADED":"UP";
            result.add(new Service(name,status,instances.size(),oldestRenewal,earliestRegistered));
        }
        return List.copyOf(result);
    }
}
