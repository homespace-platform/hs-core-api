package com.hs.contract.service.admin;

import java.time.Instant;
import java.util.List;

/** Adapter lives in assembly; domain module does not depend on Eureka. */
public interface AdminServiceDiscovery {
    record Service(String name,String status,int instances,Long lastRenewalAgeSec,Instant registeredAt) {}
    List<Service> snapshot(Instant now);
}
