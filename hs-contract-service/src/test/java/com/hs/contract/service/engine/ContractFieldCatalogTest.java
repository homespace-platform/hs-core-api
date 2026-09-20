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
    void testCatalogDefinitionsCountAndFields() {
        assertEquals(63, catalog.getAllDefinitions().size(), "Catalog must have exactly 63 field definitions");

        // Valid fields from CONTRACT_PROMPT.md
        assertTrue(catalog.isValidPlaceholder("contract.number"));
        assertTrue(catalog.isValidPlaceholder("contract.signingDate"));
        assertTrue(catalog.isValidPlaceholder("contract.signingCity"));
        assertTrue(catalog.isValidPlaceholder("landlord.fullName"));
        assertTrue(catalog.isValidPlaceholder("landlord.idNumber"));
        assertTrue(catalog.isValidPlaceholder("landlord.permanentAddress"));
        assertTrue(catalog.isValidPlaceholder("landlord.phone"));
        assertTrue(catalog.isValidPlaceholder("landlord.email"));
        assertTrue(catalog.isValidPlaceholder("tenant.fullName"));
        assertTrue(catalog.isValidPlaceholder("tenant.idNumber"));
        assertTrue(catalog.isValidPlaceholder("tenant.permanentAddress"));
        assertTrue(catalog.isValidPlaceholder("tenant.phone"));
        assertTrue(catalog.isValidPlaceholder("tenant.email"));
        assertTrue(catalog.isValidPlaceholder("tenant.occupantCount"));
        assertTrue(catalog.isValidPlaceholder("tenant.motorbikeCount"));
        assertTrue(catalog.isValidPlaceholder("tenant.carCount"));
        assertTrue(catalog.isValidPlaceholder("property.fullAddress"));
        assertTrue(catalog.isValidPlaceholder("property.areaText"));
        assertTrue(catalog.isValidPlaceholder("property.propertyType"));
        assertTrue(catalog.isValidPlaceholder("property.unitNumber"));
        assertTrue(catalog.isValidPlaceholder("property.floor"));
        assertTrue(catalog.isValidPlaceholder("lease.startDateText"));
        assertTrue(catalog.isValidPlaceholder("lease.endDateText"));
        assertTrue(catalog.isValidPlaceholder("lease.durationMonths"));
        assertTrue(catalog.isValidPlaceholder("lease.durationText"));
        assertTrue(catalog.isValidPlaceholder("lease.handoverDateText"));
        assertTrue(catalog.isValidPlaceholder("rent.amountNumber"));
        assertTrue(catalog.isValidPlaceholder("rent.amountWords"));
        assertTrue(catalog.isValidPlaceholder("rent.paymentCycle"));
        assertTrue(catalog.isValidPlaceholder("rent.paymentDueDay"));
        assertTrue(catalog.isValidPlaceholder("rent.paymentMethod"));
        assertTrue(catalog.isValidPlaceholder("deposit.amountNumber"));
        assertTrue(catalog.isValidPlaceholder("deposit.amountWords"));
        assertTrue(catalog.isValidPlaceholder("deposit.description"));
        assertTrue(catalog.isValidPlaceholder("meters.electricityInitial"));
        assertTrue(catalog.isValidPlaceholder("meters.waterInitial"));
        assertTrue(catalog.isValidPlaceholder("#chargesTable"));
        assertTrue(catalog.isValidPlaceholder("#equipmentTable"));
        // V2 placeholders
        assertTrue(catalog.isValidPlaceholder("contract.schemaVersion"));
        assertTrue(catalog.isValidPlaceholder("contract.revisionNumber"));
        assertTrue(catalog.isValidPlaceholder("contract.specialTerms"));
        assertTrue(catalog.isValidPlaceholder("property.listingCode"));
        assertTrue(catalog.isValidPlaceholder("property.rentalScope"));
        assertTrue(catalog.isValidPlaceholder("property.maxOccupants"));
        assertTrue(catalog.isValidPlaceholder("property.maxVehicles"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.status"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.paidAt"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.transactionCode"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.totalAmount"));
        assertTrue(catalog.isValidPlaceholder("#propertyFeaturesTable"));
        assertTrue(catalog.isValidPlaceholder("#amenitiesTable"));
        assertTrue(catalog.isValidPlaceholder("#initialPaymentTable"));

        // Schema V3 placeholders
        assertTrue(catalog.isValidPlaceholder("landlord.bankName"));
        assertTrue(catalog.isValidPlaceholder("landlord.bankAccountNumber"));
        assertTrue(catalog.isValidPlaceholder("landlord.bankAccountHolder"));
        assertTrue(catalog.isValidPlaceholder("tenant.bankName"));
        assertTrue(catalog.isValidPlaceholder("tenant.bankAccountNumber"));
        assertTrue(catalog.isValidPlaceholder("tenant.bankAccountHolder"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.payerReportedAt"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.payeeConfirmedAt"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.confirmedAt"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.transferReference"));
        assertTrue(catalog.isValidPlaceholder("payment.initial.bankTransactionReference"));

        // Invalid / removed fields
        assertFalse(catalog.isValidPlaceholder("lease.rentalMode"), "lease.rentalMode must not be valid");
        assertFalse(catalog.isValidPlaceholder("tenant.organizationName"), "tenant.organizationName must not be valid");
        assertFalse(catalog.isValidPlaceholder("tenant.representativeName"), "tenant.representativeName must not be valid");
        assertFalse(catalog.isValidPlaceholder("tenant.representativePosition"), "tenant.representativePosition must not be valid");
    }

    @Test
    void testSupportedCategories() {
        assertEquals(3, ContractFieldCatalog.SUPPORTED_CATEGORIES.size());
        assertTrue(ContractFieldCatalog.SUPPORTED_CATEGORIES.contains(com.hs.listing.model.constant.ListingCategory.HOUSE));
        assertTrue(ContractFieldCatalog.SUPPORTED_CATEGORIES.contains(com.hs.listing.model.constant.ListingCategory.APARTMENT));
        assertTrue(ContractFieldCatalog.SUPPORTED_CATEGORIES.contains(com.hs.listing.model.constant.ListingCategory.ROOM));
        assertFalse(ContractFieldCatalog.SUPPORTED_CATEGORIES.contains(com.hs.listing.model.constant.ListingCategory.OFFICE));
        assertFalse(ContractFieldCatalog.SUPPORTED_CATEGORIES.contains(com.hs.listing.model.constant.ListingCategory.COMMERCIAL_SPACE));
    }

    @Test
    void testRequiredDefinitionsPerCategory() {
        // HOUSE template does not require property.unitNumber
        var houseRequired = catalog.getRequiredDefinitions(com.hs.listing.model.constant.ListingCategory.HOUSE);
        assertFalse(houseRequired.stream().anyMatch(d -> "property.unitNumber".equals(d.getKey())),
                "HOUSE template should not require property.unitNumber");
        assertFalse(houseRequired.stream().anyMatch(d -> "lease.rentalMode".equals(d.getKey())),
                "HOUSE template should not require lease.rentalMode");

        // APARTMENT and ROOM require property.unitNumber
        var apartmentRequired = catalog.getRequiredDefinitions(com.hs.listing.model.constant.ListingCategory.APARTMENT);
        assertTrue(apartmentRequired.stream().anyMatch(d -> "property.unitNumber".equals(d.getKey())),
                "APARTMENT template must require property.unitNumber");

        var roomRequired = catalog.getRequiredDefinitions(com.hs.listing.model.constant.ListingCategory.ROOM);
        assertTrue(roomRequired.stream().anyMatch(d -> "property.unitNumber".equals(d.getKey())),
                "ROOM template must require property.unitNumber");
    }
}
