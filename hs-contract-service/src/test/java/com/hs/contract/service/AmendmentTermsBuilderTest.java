package com.hs.contract.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.model.ContractRevision;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AmendmentTermsBuilderTest {
    AmendmentTermsBuilder builder = new AmendmentTermsBuilder(new ObjectMapper());
    ContractRevision base() { return ContractRevision.builder().financialSnapshot("{\"amountValue\":\"1000000\"}")
            .tenantSnapshot("{\"occupantCount\":2,\"motorbikeCount\":1,\"carCount\":0}")
            .propertySnapshot("{\"maxOccupants\":3}").policiesSnapshot("{\"petsAllowed\":false,\"latePaymentFeeMode\":\"NONE\"}")
            .chargesSnapshot("[{\"name\":\"Điện\",\"chargeType\":\"ELECTRICITY\",\"billingMethod\":\"PER_KWH\",\"unitAmount\":\"3500\",\"includedInRent\":false,\"amountAndMethod\":\"3.500/kWh\"}]").build(); }
    @Test void meterRateChangesAreCanonicalButMeterMethodCannotChange() {
        var base = base(); var rows = builder.rows(base.getChargesSnapshot()); rows.get(0).put("unitAmount","4000");
        var result = builder.build(base,Map.of("charges",rows));
        assertTrue(builder.rows(builder.write(result.terms().get("charges"))).get(0).get("amountAndMethod").toString().contains("4.000"));
        rows.get(0).put("billingMethod","PER_MONTH"); assertThrows(AppException.class,() -> builder.build(base,Map.of("charges",rows)));
        assertThrows(AppException.class,() -> builder.build(base,Map.of("charges",List.of())));
    }
    @Test void numericAndOccupancyLimitsCannotBeBypassed() {
        for (String value : List.of("-1","0","1.5","1000000001","bad")) assertThrows(AppException.class,() -> builder.build(base(),Map.of("rentAmount",value)));
        assertThrows(AppException.class,() -> builder.build(base(),Map.of("occupantCount",4)));
        assertThrows(AppException.class,() -> builder.build(base(),Map.of("occupantCount",1.5)));
    }
    @Test void paymentPoliciesAndSignedIdentityAreProtected() {
        for (String field : List.of("landlord","tenant","lease","meters","initialPayment","depositAmount","bankAccountNumber"))
            assertThrows(AppException.class,() -> builder.build(base(),Map.of(field,"forged")));
        assertThrows(AppException.class,() -> builder.build(base(),Map.of("policies",Map.of("latePaymentFeeAmount",100000))));
    }
    @Test void supplementaryRulesPreserveOldClausesAndStructuredPolicies() {
        var base = base(); base.setSpecialTerms("Điều khoản gốc");
        var result = builder.build(base,Map.of("policies",Map.of("petsAllowed",true),"additionalTerms","Bổ sung quy định khách qua đêm"));
        assertEquals(true,builder.object(result.terms().get("policies")).get("petsAllowed"));
        assertEquals("NONE",builder.object(result.terms().get("policies")).get("latePaymentFeeMode"));
        assertTrue(result.terms().get("specialTerms").toString().startsWith("Điều khoản gốc"));
    }
    @Test void changingFeesDoesNotInventAmenityFields() {
        var base = base(); base.setAmenitiesSnapshot("[{\"code\":\"WIFI\",\"name\":\"WiFi\"}]");
        var rows = builder.rows(base.getChargesSnapshot()); rows.add(new LinkedHashMap<>(Map.of("name","WiFi","chargeType","INTERNET","billingMethod","PER_MONTH","unitAmount","90000","includedInRent",false)));
        base.setChargesSnapshot(builder.write(rows)); rows.getLast().put("unitAmount","100000");
        var result = builder.build(base,Map.of("charges",rows));
        assertEquals(Set.of("code","name"),builder.rows(builder.write(result.terms().get("amenities"))).getFirst().keySet());
    }
    @Test void feeIdentityAndUnitsCannotBeForgedAndMethodTextIsRebuilt() {
        var base = base();
        var rows = builder.rows(base.getChargesSnapshot());
        rows.add(new LinkedHashMap<>(Map.of("name","WiFi","chargeType","INTERNET","billingMethod","PER_MONTH","unitAmount","90000","includedInRent",false)));
        base.setChargesSnapshot(builder.write(rows));
        for (String method : List.of("PER_VEHICLE_MONTH","PER_PERSON_MONTH","PER_M2_MONTH","PER_KWH")) {
            rows.getLast().put("billingMethod",method);
            assertThrows(AppException.class,() -> builder.build(base,Map.of("charges",rows)));
        }
        rows.getLast().put("billingMethod","FREE"); rows.getLast().put("billingMethodText","Sai: xe/tháng");
        var result = builder.build(base,Map.of("charges",rows));
        var wifi = builder.rows(builder.write(result.terms().get("charges"))).getLast();
        assertEquals("0",wifi.get("unitAmount")); assertEquals("Miễn phí",wifi.get("billingMethodText"));
        assertEquals("Miễn phí",wifi.get("amountAndMethod")); assertEquals(true,wifi.get("includedInRent"));
        rows.getLast().put("chargeType","CAR_PARKING"); rows.getLast().put("billingMethod","PER_VEHICLE_MONTH");
        assertThrows(AppException.class,() -> builder.build(base,Map.of("charges",rows)));
    }
    @Test void managementUsesFrozenAreaAndBlankOptionalCapacityDoesNotBlockRentChanges() {
        var base = base(); base.setPropertySnapshot("{\"areaM2\":\"75.5\",\"maxOccupants\":\"\"}");
        builder.build(base,Map.of("rentAmount","1100000"));
        var rows = builder.rows(base.getChargesSnapshot());
        rows.add(new LinkedHashMap<>(Map.of("name","Quản lý","chargeType","MANAGEMENT","billingMethod","PER_M2_MONTH","unitAmount","10000","includedInRent",false)));
        base.setChargesSnapshot(builder.write(rows)); rows.getLast().put("unitAmount","12000");
        var fee = builder.rows(builder.write(builder.build(base,Map.of("charges",rows)).terms().get("charges"))).getLast();
        assertEquals("906000",fee.get("estimatedMonthlyAmount")); assertEquals("m² / tháng",fee.get("billingMethodText"));
    }
    @Test void personAndVehicleFeesRecalculateWithoutInheritedListingVehicleLimits() {
        var base = base(); base.setPropertySnapshot("{\"maxOccupants\":3,\"maxVehicles\":2,\"maxCarCount\":0}");
        var rows = builder.rows(base.getChargesSnapshot());
        rows.add(new LinkedHashMap<>(Map.of("name","Rác","chargeType","SERVICE_OR_GARBAGE","billingMethod","PER_PERSON_MONTH","unitAmount","30000","includedInRent",false)));
        rows.add(new LinkedHashMap<>(Map.of("name","Xe máy","chargeType","MOTORBIKE_PARKING","billingMethod","PER_VEHICLE_MONTH","unitAmount","50000","includedInRent",false)));
        base.setChargesSnapshot(builder.write(rows));
        var fees = builder.rows(builder.write(builder.build(base,Map.of("occupantCount",3,"motorbikeCount",2)).terms().get("charges")));
        assertEquals("90000",fees.get(1).get("estimatedMonthlyAmount")); assertEquals("100000",fees.get(2).get("estimatedMonthlyAmount"));
        var expanded = builder.build(base,Map.of("motorbikeCount",3,"carCount",1));
        assertEquals(3,builder.object(expanded.terms().get("tenant")).get("motorbikeCount"));
        assertEquals(1,builder.object(expanded.terms().get("tenant")).get("carCount"));
        assertEquals("150000",builder.rows(builder.write(expanded.terms().get("charges"))).get(2).get("estimatedMonthlyAmount"));
        assertThrows(AppException.class,() -> builder.build(base,Map.of("occupantCount",4)));
    }
    @Test void changingPolicyDoesNotRewritePreviouslySignedAmenitySnapshot() {
        var base = base(); base.setPoliciesSnapshot("{\"petsAllowed\":true}");
        base.setAmenitiesSnapshot("[{\"code\":\"PETS_ALLOWED\",\"name\":\"Vật nuôi\",\"scope\":\"Riêng\",\"costText\":\"Miễn phí\"}]");
        var result = builder.build(base,Map.of("policies",Map.of("petsAllowed",false)));
        assertEquals(builder.rows(base.getAmenitiesSnapshot()),builder.rows(builder.write(result.terms().get("amenities"))));
    }
}
