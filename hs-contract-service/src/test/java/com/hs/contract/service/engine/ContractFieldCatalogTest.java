package com.hs.contract.service.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContractFieldCatalogTest {

    private ContractFieldCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new ContractFieldCatalog();
    }

    @Test
    void testValidPlaceholders() {
        assertTrue(catalog.isValidPlaceholder("landlord.fullName"));
        assertTrue(catalog.isValidPlaceholder("{{landlord.fullName}}"));
        assertTrue(catalog.isValidPlaceholder("tenant.fullName"));
        assertTrue(catalog.isValidPlaceholder("property.fullAddress"));
        assertTrue(catalog.isValidPlaceholder("rent.amountNumber"));
        assertTrue(catalog.isValidPlaceholder("#chargesTable"));
    }

    @Test
    void testInvalidPlaceholders() {
        assertFalse(catalog.isValidPlaceholder("tenant.fulName")); // sai chính tả
        assertFalse(catalog.isValidPlaceholder("random_field"));
        assertFalse(catalog.isValidPlaceholder("landlord.age"));
    }
}
