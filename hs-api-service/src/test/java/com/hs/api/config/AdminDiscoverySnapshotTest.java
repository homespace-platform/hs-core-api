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
    @Test void healthFollowsInstanceStatusNotTheCachedRenewalTimeWithoutNetworkCalls(){
        Instant now=Instant.parse("2026-11-28T05:00:00Z");var client=mock(EurekaClient.class);ObjectProvider<EurekaClient> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(client);
        var applications=new Applications();var app=new Application("HS-CORE-API");
        app.addInstance(InstanceInfo.Builder.newBuilder().setInstanceId("test").setAppName("HS-CORE-API").setStatus(InstanceInfo.InstanceStatus.UP)
            .setLeaseInfo(LeaseInfo.Builder.newBuilder().setRenewalTimestamp(now.minusSeconds(120).toEpochMilli()).setRegistrationTimestamp(now.minusSeconds(500).toEpochMilli()).build()).build());applications.addApplication(app);when(client.getApplications()).thenReturn(applications);
        var result=new AdminDiscoverySnapshot(provider).snapshot(now);assertEquals(6,result.size());
        // A 120 s old cached renewal time is normal with delta fetches: the instance is UP.
        assertEquals("DOWN",result.getFirst().status());assertEquals("UP",result.get(1).status());assertNull(result.get(1).lastRenewalAgeSec());
        app.addInstance(InstanceInfo.Builder.newBuilder().setInstanceId("starting").setAppName("HS-CORE-API").setStatus(InstanceInfo.InstanceStatus.STARTING).build());
        assertEquals("DEGRADED",new AdminDiscoverySnapshot(provider).snapshot(now).get(1).status());
        verify(client,times(2)).getApplications();verifyNoMoreInteractions(client);
    }
    @Test void unavailableDiscoveryIsNotHealthy(){ObjectProvider<EurekaClient> provider=mock(ObjectProvider.class);assertThrows(IllegalStateException.class,()->new AdminDiscoverySnapshot(provider).snapshot(Instant.now()));}
}
