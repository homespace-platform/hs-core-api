package com.hs.api.config;

import com.hs.contract.notification.OperationalIncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Runs outside Gateway so a Gateway outage can still enter the durable notification channel. */
@Component @RequiredArgsConstructor @Slf4j
public class OperationalHealthMonitor {
    private final DiscoveryClient discovery;
    private final OperationalIncidentService incidents;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private int failures;

    @Scheduled(initialDelay=120000,fixedDelay=60000)
    public void checkGateway(){
        boolean healthy=false;
        try{
            for(var instance:discovery.getInstances("hs-gateway-server")){
                try {
                    var request=HttpRequest.newBuilder(URI.create(instance.getUri()+"/actuator/health/liveness"))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                    var response=http.send(request,HttpResponse.BodyHandlers.discarding());
                    if(response.statusCode()==200){healthy=true;break;}
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
                catch(Exception unavailable){ /* Another registered instance may be healthy. */ }
            }
        }
        catch(Exception unavailable){ /* Count a bounded observation; never log discovery or endpoint secrets. */ }
        failures=healthy?0:Math.min(failures+1,3);
        try{if(healthy||failures>=3)incidents.observe("gateway","GATEWAY",null,!healthy);}
        catch(RuntimeException unavailable){log.warn("Gateway incident observation deferred");}
    }
}
