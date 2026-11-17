package com.hs.api.config;

import com.hs.contract.notification.OperationalIncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OperationalHealthMonitorTest {
    @Test void onlyConsecutiveFailuresOpenAnIncidentAndHealthReportsRecovery() throws Exception {
        var discovery=mock(DiscoveryClient.class);var incidents=mock(OperationalIncidentService.class);
        var http=mock(HttpClient.class);var response=mock(HttpResponse.class);
        var monitor=new OperationalHealthMonitor(discovery,incidents);ReflectionTestUtils.setField(monitor,"http",http);
        when(discovery.getInstances("hs-gateway-server")).thenReturn(List.of());
        monitor.checkGateway();monitor.checkGateway();verifyNoInteractions(incidents);
        monitor.checkGateway();verify(incidents).observe("gateway","GATEWAY",null,true);
        when(discovery.getInstances("hs-gateway-server")).thenReturn(List.of(new DefaultServiceInstance("gateway","hs-gateway-server","localhost",8080,false)));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);when(response.statusCode()).thenReturn(200);
        monitor.checkGateway();verify(incidents).observe("gateway","GATEWAY",null,false);
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        org.junit.jupiter.api.Assertions.assertEquals("http://localhost:8080/actuator/health/liveness",request.getValue().uri().toString());
        when(discovery.getInstances("hs-gateway-server")).thenReturn(List.of());
        monitor.checkGateway();verifyNoMoreInteractions(incidents);
    }

    @Test void checksOtherRegisteredInstancesAfterOneIsUnreachable() throws Exception {
        var discovery=mock(DiscoveryClient.class);var incidents=mock(OperationalIncidentService.class);
        var http=mock(HttpClient.class);var response=mock(HttpResponse.class);
        var monitor=new OperationalHealthMonitor(discovery,incidents);ReflectionTestUtils.setField(monitor,"http",http);
        when(discovery.getInstances("hs-gateway-server")).thenReturn(List.of(
            new DefaultServiceInstance("first","hs-gateway-server","localhost",8080,false),
            new DefaultServiceInstance("second","hs-gateway-server","localhost",8081,false)));
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
            .thenThrow(new java.io.IOException("unreachable")).thenReturn(response);
        when(response.statusCode()).thenReturn(200);
        monitor.checkGateway();
        verify(incidents).observe("gateway","GATEWAY",null,false);
        verify(http,times(2)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
    }
}
