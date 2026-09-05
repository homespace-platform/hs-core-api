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
        assertTrue(dummy.containsKey("landlord.fullName"));
        assertTrue(dummy.containsKey("tenant.fullName"));
        assertTrue(dummy.containsKey("property.fullAddress"));
        assertTrue(dummy.containsKey("chargesTable"));
        assertTrue(dummy.containsKey("equipmentTable"));
    }
}
