package com.hs.contract.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContractPolicyTermsTest {
    @Test void emptyHistoricalPolicyDoesNotInventThirtyOrFifteenDays() {
        assertEquals("", ContractPolicyTerms.clause(null));
        assertEquals("", ContractPolicyTerms.clause(Map.of()));
    }
    @Test void explicitFlagsAndTextPoliciesHaveSamePrecedenceAsUi() {
        String text = ContractPolicyTerms.clause(Map.of("petPolicy", "Được phép theo nội quy", "petsAllowed", false,
                "subleasePolicy", "Chỉ khi Bên A chấp thuận", "smokingPolicy", "Không hút tại khu vực chung"));
        assertTrue(text.contains("Nuôi thú cưng: Không được phép"));
        assertFalse(text.contains("Được phép theo nội quy"));
        assertTrue(text.contains("Cho thuê lại: Chỉ khi Bên A chấp thuận"));
        assertTrue(text.contains("Hút thuốc: Không hút tại khu vực chung"));
    }
}
