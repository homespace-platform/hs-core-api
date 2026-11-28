package com.hs.common.security;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminDashboardPermissionsTest {
    @Test void dashboardPermissionsAreCataloguedAndNeverUserGrantable(){
        for(String p:List.of(Permissions.STATISTICS_VIEW,Permissions.SYSTEM_HEALTH_VIEW,Permissions.PAYMENT_VIEW,Permissions.BLOCKCHAIN_VIEW,Permissions.BLOCKCHAIN_MANAGE)){
            assertTrue(Permissions.CATALOG.containsKey(p));assertFalse(Permissions.isUserScope(p));assertFalse(Set.of("ADMIN","USER").contains(p));
        }
        assertTrue(Permissions.CATALOG.containsKey(Permissions.KYC_VIEW));
    }
}
