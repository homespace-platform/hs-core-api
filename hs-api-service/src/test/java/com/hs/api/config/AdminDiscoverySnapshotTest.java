package com.hs.api.config;

import com.netflix.appinfo.*;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.shared.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminDiscoverySnapshotTest {
    @Test void missingStaleAndHealthyInstancesAreDistinguishedWithoutNetworkCalls(){
        Instant now=Instant.parse("2026-11-28T05:00:00Z");var client=mock(EurekaClient.class);ObjectProvider<EurekaClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var applications=new Applications();var app=new Application("HS-CORE-API");
        app.addInstance(InstanceInfo.Builder.newBuilder().setInstanceId("test").setAppName("HS-CORE-API").setStatus(InstanceInfo.InstanceStatus.UP)
            .setLeaseInfo(LeaseInfo.Builder.newBuilder().setRenewalTimestamp(now.minusSeconds(120).toEpochMilli()).setRegistrationTimestamp(now.minusSeconds(500).toEpochMilli()).build()).build());applications.addApplication(app);when(client.getApplications()).thenReturn(applications);
        var result=new AdminDiscoverySnapshot(provider).snapshot(now);assertEquals(6,result.size());
        assertEquals("DOWN",result.getFirst().status());assertEquals("DEGRADED",result.get(1).status());assertEquals(120,result.get(1).lastRenewalAgeSec());
        var healthy=new AdminDiscoverySnapshot(provider).snapshot(now.minusSeconds(110));assertEquals("UP",healthy.get(1).status());
        verify(client,times(2)).getApplications();verifyNoMoreInteractions(client);
    }
    @Test void unavailableDiscoveryIsNotHealthy(){ObjectProvider<EurekaClient> provider=mock(ObjectProvider.class);assertThrows(IllegalStateException.class,()->new AdminDiscoverySnapshot(provider).snapshot(Instant.now()));}
}
