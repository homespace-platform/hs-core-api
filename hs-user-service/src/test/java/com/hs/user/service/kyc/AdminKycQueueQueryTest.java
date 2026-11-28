package com.hs.user.service.kyc;

import com.hs.common.advice.entity.AppException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdminKycQueueQueryTest {
    @Test void validatesPaginationAndOnlyKnownFilters(){
        var q=AdminKycQueueService.Query.parse(Map.of("page",new String[]{"2"},"size",new String[]{"50"},"overdue",new String[]{"false"},"status",new String[]{"REJECTED"}),true);
        assertEquals(2,q.page());assertEquals(50,q.size());assertEquals(false,q.overdue());
        for(var p:List.of(Map.of("size",new String[]{"51"}),Map.of("page",new String[]{"-1"}),Map.of("page",new String[]{"10001"}),Map.of("status",new String[]{"bad"}),Map.of("overdue",new String[]{"TRUE"}),Map.of("provider",new String[]{"VNPT","ADMIN"}),Map.of("userId",new String[]{"other"}),Map.of("status",new String[]{""})))assertThrows(AppException.class,()->AdminKycQueueService.Query.parse(p,true));
        assertThrows(AppException.class,()->AdminKycQueueService.Query.parse(Map.of("page",new String[]{"1"}),false));
    }
}
