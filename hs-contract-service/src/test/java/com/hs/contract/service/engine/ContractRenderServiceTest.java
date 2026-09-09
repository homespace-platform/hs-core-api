package com.hs.contract.service.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractRenderServiceTest {

    private final ContractRenderService renderService = new ContractRenderService(new ObjectMapper());

    @Test
    void testBuildDummyDataModel() {
        Map<String, Object> dummy = renderService.buildDummyDataModel();
        assertNotNull(dummy);
        assertTrue(dummy.containsKey("landlord"));
        assertTrue(dummy.containsKey("tenant"));
        assertTrue(dummy.containsKey("property"));
        assertTrue(dummy.containsKey("chargesTable"));
        assertTrue(dummy.containsKey("equipmentTable"));
        assertEquals("Nguyễn Văn An (Chủ nhà)", ContractRenderService.resolvePath(dummy, "landlord.fullName"));
        assertEquals("HD-20260905-DEMO", ContractRenderService.resolvePath(dummy, "contract.number"));
        assertNotNull(ContractRenderService.resolvePath(dummy, "rent.amountNumber"));
    }
}
